"""Train one or more anomaly detection models.

    python -m scripts.train --model arima
    python -m scripts.train --model sarima
    python -m scripts.train --model lstm --nasa-years 3
    python -m scripts.train --model all --lookback-days 30 --nasa-years 3

ARIMA + SARIMA train on Mongo MQTT temperature (univariate hourly).
LSTM trains on NASA POWER multivariate hourly (no Mongo dependency for fit).
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone

from ml.arima_model import ArimaDetector
from ml.config import ARTIFACTS_DIR
from ml.data import load_sensor_series
from ml.lstm_model import LstmDetector
from ml.nasa_loader import fetch_years
from ml.sarima_model import SarimaDetector


def _load_mongo_series(lookback_days: int):
    start = datetime.now(timezone.utc) - timedelta(days=lookback_days)
    series = load_sensor_series(start=start)
    if series.dropna().empty:
        raise RuntimeError(
            f"No hourly temperature data for the last {lookback_days} days. "
            f"Run `python -m scripts.check_data` to inspect."
        )
    return series


def train_arima(lookback_days: int) -> None:
    print(f"[ARIMA] Loading last {lookback_days}d hourly temperature from Mongo...")
    series = _load_mongo_series(lookback_days)
    print(f"  {len(series)} hourly points")

    det = ArimaDetector()
    print(f"  Fitting ARIMA{det.order}...")
    det.fit(series)
    out = ARTIFACTS_DIR / "arima.pkl"
    det.save(out)
    print(f"  Saved → {out} (residual_std={det.residual_std:.3f}°C)")


def train_sarima(lookback_days: int) -> None:
    print(f"[SARIMA] Loading last {lookback_days}d hourly temperature from Mongo...")
    series = _load_mongo_series(lookback_days)
    print(f"  {len(series)} hourly points")

    det = SarimaDetector()
    print(f"  Fitting SARIMA{det.order}{det.seasonal_order}... (slow)")
    det.fit(series)
    out = ARTIFACTS_DIR / "sarima.pkl"
    det.save(out)
    print(f"  Saved → {out} (residual_std={det.residual_std:.3f}°C)")


def train_lstm(years: int) -> None:
    print(f"[LSTM] Fetching {years} years of NASA POWER hourly data...")
    df = fetch_years(years=years)
    if df.empty:
        raise RuntimeError("NASA fetch returned empty — check network or API status")
    print(f"  {len(df)} hourly NASA rows")

    det = LstmDetector()
    print(f"  Training LSTM (window={det.window}, epochs={det.epochs})... (slow)")
    det.fit(df)
    out = ARTIFACTS_DIR / "lstm"
    det.save(out)
    print(f"  Saved → {out} (residual_std={det.residual_std:.3f}°C)")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=["arima", "sarima", "lstm", "all"], required=True)
    ap.add_argument("--lookback-days", type=int, default=30, help="Mongo lookback for ARIMA/SARIMA")
    ap.add_argument("--nasa-years", type=int, default=3, help="NASA history span for LSTM")
    args = ap.parse_args()

    if args.model in ("arima", "all"):
        train_arima(args.lookback_days)
    if args.model in ("sarima", "all"):
        train_sarima(args.lookback_days)
    if args.model in ("lstm", "all"):
        train_lstm(args.nasa_years)


if __name__ == "__main__":
    main()
