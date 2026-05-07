"""Detector backtest -- precision/recall/F1 vs k, with synthetic anomaly injection.

    python -m scripts.evaluate_detection                                # k=3.0, default detectors
    python -m scripts.evaluate_detection --k 5.0
    python -m scripts.evaluate_detection --sweep-k 1.0 10.0 0.5
    python -m scripts.evaluate_detection --sweep-k 1.0 10.0 0.5 --sweep-out sweep.csv
    python -m scripts.evaluate_detection --methods zscore,seasonal_zscore
    python -m scripts.evaluate_detection --methods zscore,lstm_residual   # composition demo
    python -m scripts.evaluate_detection --inject spike,drift

Default mode is REFIT: every detector is fit fresh on the train slice for
each evaluation. ML-residual detectors lift their order/architecture from
the saved artifact metadata but re-fit parameters on the train slice -- no
test-data leakage.
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

# Allow file-mode invocation in addition to `python -m scripts.evaluate_detection`.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from ml.base import AnomalyDetector  # noqa: E402
from ml.config import ARTIFACTS_DIR  # noqa: E402
from ml.detectors import (  # noqa: E402
    ResidualDetector,
    SeasonalZScoreDetector,
    ZScoreDetector,
)
from ml.forecasters import ArimaForecaster, LstmForecaster, SarimaForecaster  # noqa: E402


# Each entry maps a CLI name to a callable that builds a fresh detector for refit.
# ML-residual detectors are exposed via composition so the comparison is honest:
# `lstm_residual` evaluates the LSTM forecaster wrapped as a detector.
def _build_detector(name: str) -> AnomalyDetector:
    if name == "zscore":
        return ZScoreDetector()
    if name == "seasonal_zscore":
        return SeasonalZScoreDetector()
    if name == "arima_residual":
        return ResidualDetector(_build_forecaster("arima"), name="arima_residual")
    if name == "sarima_residual":
        return ResidualDetector(_build_forecaster("sarima"), name="sarima_residual")
    if name == "lstm_residual":
        return ResidualDetector(_build_forecaster("lstm"), name="lstm_residual")
    raise SystemExit(f"Unknown detector: {name}")


def _build_forecaster(name: str):
    """Build a forecaster, lifting hyperparameters from its artifact if present."""
    artifact_paths = {
        "arima": ARTIFACTS_DIR / "arima.pkl",
        "sarima": ARTIFACTS_DIR / "sarima.pkl",
        "lstm": ARTIFACTS_DIR / "lstm",
    }
    cls_map = {
        "arima": ArimaForecaster,
        "sarima": SarimaForecaster,
        "lstm": LstmForecaster,
    }
    cls = cls_map[name]
    art = artifact_paths[name]
    if not art.exists():
        return cls()
    loaded = cls()
    try:
        loaded.load(art)
    except Exception:
        return cls()
    if name == "arima":
        return ArimaForecaster(order=loaded.order)
    if name == "sarima":
        return SarimaForecaster(
            order=loaded.order,
            seasonal_order=loaded.seasonal_order,
            auto=False,
        )
    if name == "lstm":
        return LstmForecaster(
            window=loaded.window,
            epochs=loaded.epochs,
            batch_size=loaded.batch_size,
        )
    return cls()


DEFAULT_METHODS = ["zscore", "seasonal_zscore"]
ALL_METHODS = [
    "zscore", "seasonal_zscore",
    "arima_residual", "sarima_residual", "lstm_residual",
]
DEFAULT_DATA_PATH = ARTIFACTS_DIR / "nasa" / "training_data.csv"


@dataclass
class Metrics:
    name: str
    k: float
    precision: float
    recall: float
    f1: float


@dataclass
class DetectorRun:
    """Raw scores per test point, BEFORE thresholding."""
    name: str
    scores: np.ndarray
    labels: np.ndarray


def inject_anomalies(
    series: pd.Series, patterns: list[str], seed: int = 42
) -> tuple[pd.Series, pd.Series]:
    rng = np.random.default_rng(seed)
    perturbed = series.copy()
    labels = pd.Series(False, index=series.index)
    n = len(series)

    if "spike" in patterns:
        std = series.std()
        for idx in rng.choice(n, size=max(1, n // 100), replace=False):
            sign = rng.choice([-1, 1])
            perturbed.iloc[idx] = perturbed.iloc[idx] + sign * 5 * std
            labels.iloc[idx] = True

    if "outlier" in patterns:
        hi = series.max() + 50
        for idx in rng.choice(n, size=max(1, n // 200), replace=False):
            perturbed.iloc[idx] = hi
            labels.iloc[idx] = True

    if "drift" in patterns:
        if n > 50:
            start = int(n * 0.6)
            end = min(start + 24, n)
            drift = np.linspace(0, 3 * series.std(), end - start)
            perturbed.iloc[start:end] = perturbed.iloc[start:end] + drift
            labels.iloc[start:end] = True

    return perturbed, labels


def _train_test_split_chrono(
    series: pd.Series, test_frac: float = 0.2
) -> tuple[pd.Series, pd.Series]:
    n = len(series)
    if n < 10:
        raise ValueError(f"Need at least 10 points to split, got {n}")
    split = int(n * (1 - test_frac))
    return series.iloc[:split], series.iloc[split:]


def score_detector(
    det: AnomalyDetector,
    train: pd.Series,
    test_perturbed: pd.Series,
    test_labels: pd.Series,
    multivariate_train: pd.DataFrame | None = None,
    multivariate_test: pd.DataFrame | None = None,
) -> DetectorRun:
    """Fit on train slice, then score every test point. Returns raw |z| scores
    so any k-threshold can be evaluated after the fact."""
    # Pick the right training shape: ResidualDetector(LstmForecaster) needs
    # the multivariate DataFrame; everyone else takes the univariate series.
    needs_multivariate = (
        isinstance(det, ResidualDetector)
        and isinstance(det.forecaster, LstmForecaster)
    )
    if needs_multivariate:
        if multivariate_train is None:
            raise RuntimeError("LSTM-residual refit needs multivariate_train DataFrame")
        det.fit(multivariate_train)
    else:
        det.fit(train)

    # LSTM context override for offline backtest (no Mongo).
    if isinstance(det, ResidualDetector) and isinstance(det.forecaster, LstmForecaster):
        if multivariate_test is not None:
            det.forecaster.set_offline_context(multivariate_test)

    n = len(test_perturbed)
    scores = np.full(n, np.nan)
    for i, (ts, val) in enumerate(test_perturbed.items()):
        if pd.isna(val):
            continue
        result = det.score(float(val), pd.Timestamp(ts), k=999.0)  # k irrelevant for raw
        scores[i] = float(result.score)

    return DetectorRun(
        name=det.name,
        scores=scores,
        labels=test_labels.fillna(False).to_numpy(),
    )


def metrics_at_k(run: DetectorRun, k: float) -> Metrics:
    flags = (~np.isnan(run.scores)) & (run.scores > k)
    tp = int(np.sum(flags & run.labels))
    fp = int(np.sum(flags & ~run.labels))
    fn = int(np.sum(~flags & run.labels))
    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
    return Metrics(run.name, k, precision, recall, f1)


def _print_single_k_table(rows: list[Metrics]) -> None:
    print()
    print(f"{'Method':<25} {'Precision':>10} {'Recall':>10} {'F1':>10}")
    print("-" * 60)
    for m in rows:
        print(f"{m.name:<25} {m.precision:>10.3f} {m.recall:>10.3f} {m.f1:>10.3f}")


def _print_sweep_section(name: str, sweep_rows: list[Metrics]) -> Metrics:
    best = max(sweep_rows, key=lambda m: m.f1)
    print()
    print(f"== {name} == best at k={best.k:.2f}: P={best.precision:.3f} R={best.recall:.3f} F1={best.f1:.3f}")
    print(f"  {'k':>6} {'Precision':>10} {'Recall':>10} {'F1':>10}")
    for m in sweep_rows:
        marker = " *" if m.k == best.k else ""
        print(f"  {m.k:>6.2f} {m.precision:>10.3f} {m.recall:>10.3f} {m.f1:>10.3f}{marker}")
    return best


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--methods",
        default=",".join(DEFAULT_METHODS),
        help=f"Comma-separated detector names, or 'all'. Available: {', '.join(ALL_METHODS)}",
    )
    ap.add_argument("--inject", default="spike,outlier,drift")
    ap.add_argument("--k", type=float, default=3.0)
    ap.add_argument(
        "--sweep-k",
        nargs=3,
        type=float,
        metavar=("MIN", "MAX", "STEP"),
        default=None,
    )
    ap.add_argument("--sweep-out", type=Path, default=None)
    ap.add_argument("--data", type=Path, default=DEFAULT_DATA_PATH)
    args = ap.parse_args()

    if not args.data.exists():
        raise SystemExit(
            f"Data file not found at {args.data}.\n"
            f"Run `python -m scripts.fetch_nasa` first."
        )

    print(f"Loading {args.data}...")
    df = pd.read_csv(args.data, index_col="time", parse_dates=["time"])
    series = df["temperature"].dropna()
    if len(series) < 100:
        print(f"  Only {len(series)} hourly points -- too few to evaluate.")
        return

    train, test = _train_test_split_chrono(series)
    patterns = [p.strip() for p in args.inject.split(",") if p.strip()]
    print(f"Train: {len(train)} points; test: {len(test)} points; injecting {patterns}")
    test_perturbed, test_labels = inject_anomalies(test, patterns)
    n_anomalies = int(test_labels.sum())
    print(f"  Test set has {n_anomalies} synthetic anomalies ({n_anomalies / len(test):.1%})")

    multivariate_test = df.loc[test_perturbed.index].copy()
    multivariate_test["temperature"] = test_perturbed
    multivariate_train = df.loc[train.index].copy()

    selected = ALL_METHODS if args.methods == "all" else [m.strip() for m in args.methods.split(",")]

    runs: list[DetectorRun] = []
    print("\nMode: REFIT on train slice (no test-data leakage)")
    print("Scoring detectors...")
    for name in selected:
        try:
            det = _build_detector(name)
            print(f"  Fitting + scoring {name}...")
            run = score_detector(
                det, train, test_perturbed, test_labels,
                multivariate_train=multivariate_train,
                multivariate_test=multivariate_test,
            )
            runs.append(run)
            print(f"    Scored {len(run.scores)} points")
        except Exception as e:
            print(f"  {name} failed: {e}")

    if not runs:
        return

    if args.sweep_k:
        kmin, kmax, kstep = args.sweep_k
        if kstep <= 0 or kmax < kmin:
            raise SystemExit(f"Invalid sweep range [{kmin}, {kmax}] step={kstep}")
        k_values = list(np.arange(kmin, kmax + kstep / 2, kstep))
    else:
        k_values = [args.k]

    if len(k_values) == 1:
        rows = [metrics_at_k(r, k_values[0]) for r in runs]
        _print_single_k_table(rows)
    else:
        all_rows: list[Metrics] = []
        best_per_det: list[Metrics] = []
        for run in runs:
            sweep_rows = [metrics_at_k(run, k) for k in k_values]
            best = _print_sweep_section(run.name, sweep_rows)
            best_per_det.append(best)
            all_rows.extend(sweep_rows)

        print()
        print("=== Best k per detector (max F1) ===")
        print(f"{'Method':<25} {'Best k':>8} {'Precision':>10} {'Recall':>10} {'F1':>10}")
        print("-" * 70)
        for m in best_per_det:
            print(f"{m.name:<25} {m.k:>8.2f} {m.precision:>10.3f} {m.recall:>10.3f} {m.f1:>10.3f}")

        if args.sweep_out:
            args.sweep_out.parent.mkdir(parents=True, exist_ok=True)
            out_df = pd.DataFrame([
                {"detector": m.name, "k": m.k, "precision": m.precision,
                 "recall": m.recall, "f1": m.f1}
                for m in all_rows
            ])
            out_df.to_csv(args.sweep_out, index=False)
            print(f"\nFull sweep table saved -> {args.sweep_out}")


if __name__ == "__main__":
    main()
