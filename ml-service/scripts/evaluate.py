"""Backtest framework — shared between Z-score and ML branches.

Loads historical hourly data, injects synthetic anomalies, runs each detector,
prints precision/recall/F1.

    python -m scripts.evaluate --methods all
    python -m scripts.evaluate --methods zscore,seasonal_zscore
    python -m scripts.evaluate --inject spike,drift
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass

import numpy as np
import pandas as pd

from ml.base import AnomalyDetector
from ml.data import load_sensor_series, train_test_split_chrono


# Branches register their detectors here. Base scaffold registers none.
AVAILABLE_DETECTORS: dict[str, type[AnomalyDetector]] = {}


@dataclass
class Metrics:
    name: str
    precision: float
    recall: float
    f1: float
    mae: float | None
    rmse: float | None


def inject_anomalies(
    series: pd.Series, patterns: list[str], seed: int = 42
) -> tuple[pd.Series, pd.Series]:
    """Inject synthetic anomalies. Returns (perturbed_series, label_mask)."""
    rng = np.random.default_rng(seed)
    perturbed = series.copy()
    labels = pd.Series(False, index=series.index)
    n = len(series)

    if "spike" in patterns:
        # 10 random points get a 5σ spike
        std = series.std()
        for idx in rng.choice(n, size=max(1, n // 100), replace=False):
            sign = rng.choice([-1, 1])
            perturbed.iloc[idx] = perturbed.iloc[idx] + sign * 5 * std
            labels.iloc[idx] = True

    if "outlier" in patterns:
        # Constant outliers far outside range
        hi = series.max() + 50
        for idx in rng.choice(n, size=max(1, n // 200), replace=False):
            perturbed.iloc[idx] = hi
            labels.iloc[idx] = True

    if "drift" in patterns:
        # Gradual drift over a window of 24 points
        if n > 50:
            start = int(n * 0.6)
            end = min(start + 24, n)
            drift = np.linspace(0, 3 * series.std(), end - start)
            perturbed.iloc[start:end] = perturbed.iloc[start:end] + drift
            labels.iloc[start:end] = True

    return perturbed, labels


def evaluate_detector(
    det: AnomalyDetector,
    train: pd.Series,
    test_perturbed: pd.Series,
    test_labels: pd.Series,
    k: float = 3.0,
) -> Metrics:
    det.fit(train)

    preds = []
    actuals = []
    flags = []
    for ts, val in test_perturbed.items():
        if pd.isna(val):
            flags.append(False)
            continue
        result = det.score(float(val), pd.Timestamp(ts), k=k)
        flags.append(bool(result.is_anomaly))
        if result.predicted is not None:
            preds.append(result.predicted)
            actuals.append(float(val))

    flags_arr = np.array(flags)
    labels_arr = test_labels.fillna(False).to_numpy()
    tp = int(np.sum(flags_arr & labels_arr))
    fp = int(np.sum(flags_arr & ~labels_arr))
    fn = int(np.sum(~flags_arr & labels_arr))

    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0

    mae = rmse = None
    if preds:
        residuals = np.array(preds) - np.array(actuals)
        mae = float(np.mean(np.abs(residuals)))
        rmse = float(np.sqrt(np.mean(residuals**2)))

    return Metrics(det.name, precision, recall, f1, mae, rmse)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--methods", default="all", help="Comma-separated detector names, or 'all'")
    ap.add_argument("--inject", default="spike,outlier,drift")
    ap.add_argument("--k", type=float, default=3.0)
    args = ap.parse_args()

    if not AVAILABLE_DETECTORS:
        print("No detectors registered. This is the base scaffold — checkout a")
        print("feat/anomaly-* branch which populates AVAILABLE_DETECTORS.")
        return

    print("Loading historical hourly data...")
    series = load_sensor_series().dropna()
    if len(series) < 100:
        print(f"  Only {len(series)} hourly points — too few to evaluate. Run scripts.check_data.")
        return

    train, test = train_test_split_chrono(series)
    patterns = [p.strip() for p in args.inject.split(",") if p.strip()]
    print(f"Injecting {patterns} into test set ({len(test)} points)...")
    test_perturbed, test_labels = inject_anomalies(test, patterns)

    selected = (
        list(AVAILABLE_DETECTORS.keys())
        if args.methods == "all"
        else [m.strip() for m in args.methods.split(",")]
    )

    print()
    print(f"{'Method':<25} {'Precision':>10} {'Recall':>10} {'F1':>10} {'MAE':>10} {'RMSE':>10}")
    print("-" * 80)
    for name in selected:
        if name not in AVAILABLE_DETECTORS:
            print(f"  Unknown method: {name}")
            continue
        det = AVAILABLE_DETECTORS[name]()
        m = evaluate_detector(det, train, test_perturbed, test_labels, k=args.k)
        mae_s = f"{m.mae:.3f}" if m.mae is not None else "  -"
        rmse_s = f"{m.rmse:.3f}" if m.rmse is not None else "  -"
        print(f"{m.name:<25} {m.precision:>10.3f} {m.recall:>10.3f} {m.f1:>10.3f} {mae_s:>10} {rmse_s:>10}")


if __name__ == "__main__":
    main()
