"""Forecaster backtest -- MAE / RMSE / MAPE on the CLEAN test slice.

    python -m scripts.evaluate_forecast                                  # all forecasters, h=1
    python -m scripts.evaluate_forecast --horizons 1 6 24                # multiple horizons
    python -m scripts.evaluate_forecast --methods arima,lstm
    python -m scripts.evaluate_forecast --max-points 1000                # cap test loop length

Each forecaster is fit fresh on the train slice (80%), then walked across
the unperturbed test slice in one-step-ahead mode. At each test timestamp:

  - call `predict(time, horizon)` to get h forecasts starting at that timestamp
  - compute |forecast - actual| at each horizon offset
  - call `update(actual, time)` to extend model state

Multi-horizon errors are reported as a matrix: row=method, column=horizon.
This is the apples-to-apples forecasting comparison; no anomaly injection.
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from ml.base import Forecaster  # noqa: E402
from ml.config import ARTIFACTS_DIR  # noqa: E402
from ml.forecasters import ArimaForecaster, LstmForecaster, SarimaForecaster  # noqa: E402

AVAILABLE: dict[str, type[Forecaster]] = {
    "arima": ArimaForecaster,
    "sarima": SarimaForecaster,
    "lstm": LstmForecaster,
}
ARTIFACT_PATHS = {
    "arima": ARTIFACTS_DIR / "arima.pkl",
    "sarima": ARTIFACTS_DIR / "sarima.pkl",
    "lstm": ARTIFACTS_DIR / "lstm",
}
DEFAULT_DATA_PATH = ARTIFACTS_DIR / "nasa" / "training_data.csv"


@dataclass
class HorizonMetrics:
    name: str
    horizon: int
    mae: float
    rmse: float
    mape: float
    n_eval: int


def _build_for_refit(name: str) -> Forecaster:
    cls = AVAILABLE[name]
    art = ARTIFACT_PATHS[name]
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


def _train_test_split_chrono(
    series: pd.Series, test_frac: float = 0.2
) -> tuple[pd.Series, pd.Series]:
    n = len(series)
    if n < 10:
        raise ValueError(f"Need at least 10 points to split, got {n}")
    split = int(n * (1 - test_frac))
    return series.iloc[:split], series.iloc[split:]


def evaluate_forecaster(
    fc: Forecaster,
    train_series: pd.Series,
    test_series: pd.Series,
    horizons: list[int],
    multivariate_train: pd.DataFrame | None = None,
    multivariate_test: pd.DataFrame | None = None,
    max_points: int | None = None,
) -> list[HorizonMetrics]:
    """Fit on train, walk the test set, collect (horizon -> errors) per step."""
    if isinstance(fc, LstmForecaster):
        if multivariate_train is None:
            raise RuntimeError("LSTM evaluation needs multivariate_train DataFrame")
        fc.fit(multivariate_train)
        if multivariate_test is not None:
            fc.set_offline_context(multivariate_test)
    else:
        fc.fit(train_series)

    h_max = max(horizons)
    actuals = test_series.values.astype(float)
    timestamps = test_series.index
    n = len(test_series) - h_max  # need h_max future actuals to compare against
    if max_points is not None:
        n = min(n, max_points)
    if n <= 0:
        return []

    # errors[h_idx] = list of (forecast - actual) at horizon offset h_idx+1
    errors: dict[int, list[float]] = {h: [] for h in horizons}

    for i in range(n):
        ts = pd.Timestamp(timestamps[i])
        try:
            pred = fc.predict(ts, horizon=h_max)
        except Exception:
            # If we can't predict here (e.g. LSTM context not yet built), skip
            try:
                fc.update(float(actuals[i]), ts)
            except Exception:
                pass
            continue

        for h in horizons:
            offset = i + h - 1
            if offset >= len(actuals) or h - 1 >= len(pred.forecast):
                continue
            err = pred.forecast[h - 1] - actuals[offset]
            errors[h].append(err)

        # Extend state with the true observation
        try:
            fc.update(float(actuals[i]), ts)
        except Exception:
            pass

    out: list[HorizonMetrics] = []
    for h in horizons:
        e = np.array(errors[h], dtype=float)
        if len(e) == 0:
            continue
        mae = float(np.mean(np.abs(e)))
        rmse = float(np.sqrt(np.mean(e ** 2)))
        # MAPE: ignore zero/near-zero actuals to avoid division blowup
        actual_at_h = actuals[h - 1 : h - 1 + len(e)]
        mask = np.abs(actual_at_h) > 1e-3
        mape = float(np.mean(np.abs(e[mask] / actual_at_h[mask])) * 100) if mask.any() else float("nan")
        out.append(HorizonMetrics(name=fc.name, horizon=h, mae=mae, rmse=rmse, mape=mape, n_eval=len(e)))
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--methods",
        default=",".join(AVAILABLE.keys()),
        help=f"Comma-separated forecaster names. Available: {', '.join(AVAILABLE.keys())}",
    )
    ap.add_argument(
        "--horizons",
        nargs="+",
        type=int,
        default=[1, 6, 24],
        help="Forecast horizons (in hours) to evaluate",
    )
    ap.add_argument("--data", type=Path, default=DEFAULT_DATA_PATH)
    ap.add_argument(
        "--max-points",
        type=int,
        default=None,
        help="Cap number of test starts to walk (each start triggers one predict()). "
             "Useful for LSTM where each predict is slow.",
    )
    ap.add_argument("--out", type=Path, default=None, help="Optional CSV output path")
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
    print(f"Train: {len(train)} points; test: {len(test)} points; horizons={args.horizons}")
    if args.max_points:
        print(f"  Walking at most {args.max_points} test starts per forecaster")

    multivariate_train = df.loc[train.index].copy()
    multivariate_test = df.loc[test.index].copy()  # CLEAN -- no injection here

    selected = [m.strip() for m in args.methods.split(",")]

    all_metrics: list[HorizonMetrics] = []
    for name in selected:
        if name not in AVAILABLE:
            print(f"  Unknown forecaster: {name}")
            continue
        try:
            fc = _build_for_refit(name)
            print(f"\n[{name}] Fitting + walking test set...")
            metrics = evaluate_forecaster(
                fc, train, test, args.horizons,
                multivariate_train=multivariate_train,
                multivariate_test=multivariate_test,
                max_points=args.max_points,
            )
            all_metrics.extend(metrics)
        except Exception as e:
            print(f"  {name} failed: {e}")

    if not all_metrics:
        return

    print()
    print(f"{'Method':<10} {'Horizon (h)':>12} {'MAE':>8} {'RMSE':>8} {'MAPE %':>8} {'N':>8}")
    print("-" * 60)
    for m in all_metrics:
        print(f"{m.name:<10} {m.horizon:>12d} {m.mae:>8.3f} {m.rmse:>8.3f} {m.mape:>8.2f} {m.n_eval:>8d}")

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        pd.DataFrame([
            {"method": m.name, "horizon": m.horizon, "mae": m.mae,
             "rmse": m.rmse, "mape": m.mape, "n_eval": m.n_eval}
            for m in all_metrics
        ]).to_csv(args.out, index=False)
        print(f"\nResults saved -> {args.out}")


if __name__ == "__main__":
    main()
