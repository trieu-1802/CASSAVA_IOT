"""Train one or more anomaly detection models from a pre-fetched NASA CSV.

    # ONE TIME: fetch NASA POWER data into a CSV
    python -m scripts.fetch_nasa

    # Train (reads artifacts/nasa/training_data.csv by default)
    python -m scripts.train --model arima
    python -m scripts.train --model sarima
    python -m scripts.train --model lstm
    python -m scripts.train --model all

    # Auto-select orders by AIC stepwise search:
    #   ARIMA  — pmdarima.auto_arima (statsmodels backend)
    #   SARIMA — statsforecast.AutoARIMA (memory-efficient; full-data search OK)
    python -m scripts.train --model arima --auto-order
    python -m scripts.train --model sarima --auto-order

ARIMA and SARIMA fit on the temperature column only; LSTM fits on the full
multivariate frame (temperature, relativeHumidity, wind, rain, radiation).

Pass --data <path> to use a different CSV.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Allow running as a file (`python scripts/train.py` or IDE Run button) in
# addition to module mode (`python -m scripts.train`). Module mode already
# gets ml-service/ on sys.path; file mode does not.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pandas as pd  # noqa: E402

from ml.config import ARTIFACTS_DIR  # noqa: E402
from ml.forecasters import ArimaForecaster, LstmForecaster, SarimaForecaster  # noqa: E402

DEFAULT_DATA_PATH = ARTIFACTS_DIR / "nasa" / "training_data.csv"


def _load_training_data(path: Path) -> pd.DataFrame:
    if not path.exists():
        raise SystemExit(
            f"Training data not found at {path}.\n"
            f"Run `python -m scripts.fetch_nasa` first to fetch NASA POWER data."
        )
    df = pd.read_csv(path, index_col="time", parse_dates=["time"])
    print(f"[DATA] Loaded {len(df)} hourly rows from {path}")
    print(f"       Span: {df.index[0]} .. {df.index[-1]}")
    return df


def _auto_order(
    series: pd.Series,
    seasonal: bool,
    m: int = 1,
    search_size: int = 0,
) -> tuple[tuple[int, int, int], tuple[int, int, int, int]]:
    """Stepwise AIC search via pmdarima.auto_arima.

    Returns (order, seasonal_order). Constrained search bounds keep the runtime
    reasonable on hourly data:
      ARIMA   : max_p=5,  max_q=5,  max_d=2
      SARIMA  : max_p=2,  max_q=2,  max_P=1, max_Q=1, max_d=1, max_D=1, m=24
                (each candidate is still a full SARIMA fit — slow on 50k+ rows;
                 use search_size to subsample for the search)

    If `search_size > 0`, runs the search on the LAST `search_size` points;
    the caller fits the final model on full data with the chosen order.
    """
    try:
        from pmdarima import auto_arima
    except ImportError:
        raise SystemExit(
            "pmdarima not installed. Run:\n"
            "  ml-service/.venv/Scripts/python.exe -m pip install pmdarima"
        )

    label = "SARIMA" if seasonal else "ARIMA"
    if search_size > 0 and len(series) > search_size:
        search_series = series.tail(search_size)
        print(f"  [{label}] Auto-search on last {search_size} of {len(series)} points...")
    else:
        search_series = series
        print(f"  [{label}] Auto-search on all {len(series)} points...")

    kwargs: dict = dict(
        seasonal=seasonal,
        stepwise=True,
        suppress_warnings=True,
        error_action="ignore",
        trace=True,
    )
    if seasonal:
        kwargs.update(m=m, max_p=2, max_q=2, max_P=1, max_Q=1, max_d=1, max_D=1)
    else:
        kwargs.update(max_p=5, max_q=5, max_d=2)

    fitted = auto_arima(search_series, **kwargs)
    return fitted.order, fitted.seasonal_order


def train_arima(df: pd.DataFrame, auto_order: bool = False, search_size: int = 0) -> None:
    series = df["temperature"].dropna()
    print(f"[ARIMA] Training on {len(series)} hourly NASA temperature points...")

    if auto_order:
        order, _ = _auto_order(series, seasonal=False, search_size=search_size)
        print(f"  Auto-selected order: {order}")
        det = ArimaForecaster(order=order)
    else:
        det = ArimaForecaster()

    print(f"  Fitting ARIMA{det.order}...")
    det.fit(series)
    out = ARTIFACTS_DIR / "arima.pkl"
    det.save(out)
    print(f"  Saved -> {out} (residual_std={det.residual_std:.3f}°C)")


def train_sarima(df: pd.DataFrame, auto_order: bool = False) -> None:
    series = df["temperature"].dropna()
    print(f"[SARIMA] Training on {len(series)} hourly NASA temperature points...")

    # SarimaForecaster now uses statsforecast under the hood. When auto=True,
    # AutoARIMA's stepwise search runs INSIDE fit() — no separate helper call,
    # and no need to subsample for memory (statsforecast handles full data).
    det = SarimaForecaster(auto=auto_order)

    if auto_order:
        print("  Fitting SARIMA via statsforecast AutoARIMA (stepwise search inside fit)...")
    else:
        print(f"  Fitting SARIMA{det.order}{det.seasonal_order}... (slow)")
    det.fit(series)
    out = ARTIFACTS_DIR / "sarima.pkl"
    det.save(out)
    print(f"  Saved -> {out} (residual_std={det.residual_std:.3f}°C)")


def train_lstm(df: pd.DataFrame) -> None:
    det = LstmForecaster()
    print(f"[LSTM] Training (window={det.window}, epochs={det.epochs})... (slow)")
    det.fit(df)
    out = ARTIFACTS_DIR / "lstm"
    det.save(out)
    print(f"  Saved -> {out} (residual_std={det.residual_std:.3f}°C)")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", choices=["arima", "sarima", "lstm", "all"], required=True)
    ap.add_argument(
        "--data",
        type=Path,
        default=DEFAULT_DATA_PATH,
        help="CSV produced by `python -m scripts.fetch_nasa`",
    )
    ap.add_argument(
        "--auto-order",
        action="store_true",
        help="Use pmdarima.auto_arima to stepwise-search ARIMA/SARIMA orders by AIC. "
             "Ignored for LSTM. SARIMA auto-search is slow -- pair with --auto-search-size.",
    )
    ap.add_argument(
        "--auto-search-size",
        type=int,
        default=0,
        help="When --auto-order is set, run the search on the last N points only "
             "(0 = use full data). Useful for SARIMA where each candidate fit is slow. "
             "The final model is still fit on the full series with the chosen order.",
    )
    args = ap.parse_args()

    df = _load_training_data(args.data)

    if args.model in ("arima", "all"):
        train_arima(df, auto_order=args.auto_order, search_size=args.auto_search_size)
    if args.model in ("sarima", "all"):
        # SARIMA uses statsforecast AutoARIMA which handles full-data search
        # natively; --auto-search-size only affects ARIMA's pmdarima path.
        train_sarima(df, auto_order=args.auto_order)
    if args.model in ("lstm", "all"):
        train_lstm(df)


if __name__ == "__main__":
    main()
