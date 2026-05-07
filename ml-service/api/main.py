"""FastAPI app -- detector + forecaster split.

Two roles, two endpoints:
  - /detect   -> robust statistical detectors (z-score, seasonal z-score)
                 plus any ResidualDetector wrappers around forecasters.
  - /forecast -> ML forecasters (ARIMA, SARIMA, LSTM) returning the next
                 `horizon` hourly predictions side-by-side.

Each artifact failure is independent: missing files or bad fits leave the
remaining models registered. /health and /models report the live registry.
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path

import pandas as pd
from fastapi import FastAPI

from api.registry import DETECTORS, FORECASTERS
from api.routes.detect import router as detect_router
from api.routes.forecast import router as forecast_router
from api.schemas import ModelInfo
from ml.base import AnomalyDetector, Forecaster
from ml.config import ARTIFACTS_DIR
from ml.detectors import SeasonalZScoreDetector, ZScoreDetector
from ml.forecasters import ArimaForecaster, LstmForecaster, SarimaForecaster

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)

NASA_CSV_PATH = ARTIFACTS_DIR / "nasa" / "training_data.csv"
ARIMA_PATH = ARTIFACTS_DIR / "arima.pkl"
SARIMA_PATH = ARTIFACTS_DIR / "sarima.pkl"
LSTM_PATH = ARTIFACTS_DIR / "lstm"


def _load_forecaster(name: str, cls: type[Forecaster], path: Path) -> Forecaster | None:
    if not path.exists():
        logger.warning("%s artifact missing at %s -- skipping", name, path)
        return None
    try:
        fc = cls()
        fc.load(path)
        logger.info("Loaded forecaster %s (residual_std=%s)", name, fc.residual_std)
        return fc
    except Exception:
        logger.exception("Failed to load %s from %s", name, path)
        return None


def _fit_detector(name: str, cls: type[AnomalyDetector], series: pd.Series) -> AnomalyDetector | None:
    try:
        det = cls()
        det.fit(series)
        logger.info("Fit detector %s on %d points (residual_std=%s)", name, len(series), det.residual_std)
        return det
    except Exception:
        logger.exception("Failed to fit %s", name)
        return None


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Forecasters: load from disk
    for name, cls, path in [
        ("arima", ArimaForecaster, ARIMA_PATH),
        ("sarima", SarimaForecaster, SARIMA_PATH),
        ("lstm", LstmForecaster, LSTM_PATH),
    ]:
        fc = _load_forecaster(name, cls, path)
        if fc is not None:
            FORECASTERS[name] = fc

    # Detectors: fit at startup from the same NASA CSV used for training
    if NASA_CSV_PATH.exists():
        try:
            nasa_df = pd.read_csv(NASA_CSV_PATH, index_col="time", parse_dates=["time"])
            temp = nasa_df["temperature"].dropna()
            for name, cls in [
                ("zscore", ZScoreDetector),
                ("seasonal_zscore", SeasonalZScoreDetector),
            ]:
                det = _fit_detector(name, cls, temp)
                if det is not None:
                    DETECTORS[name] = det
        except Exception:
            logger.exception("Failed to load NASA CSV for detector fit")
    else:
        logger.warning(
            "NASA CSV missing at %s -- detectors will not fit. "
            "Run `python -m scripts.fetch_nasa` first.",
            NASA_CSV_PATH,
        )

    logger.info(
        "ml-service starting; %d detectors=%s, %d forecasters=%s",
        len(DETECTORS), list(DETECTORS.keys()),
        len(FORECASTERS), list(FORECASTERS.keys()),
    )
    yield
    logger.info("ml-service shutting down")


app = FastAPI(title="Cassava ML Service", version="0.3.0", lifespan=lifespan)
app.include_router(detect_router)
app.include_router(forecast_router)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "detectors": list(DETECTORS.keys()),
        "forecasters": list(FORECASTERS.keys()),
    }


@app.get("/models", response_model=list[ModelInfo])
def list_models() -> list[ModelInfo]:
    out: list[ModelInfo] = []
    for name, det in DETECTORS.items():
        out.append(ModelInfo(name=name, role="detector", loaded=True, residual_std=det.residual_std))
    for name, fc in FORECASTERS.items():
        out.append(ModelInfo(name=name, role="forecaster", loaded=True, residual_std=fc.residual_std))
    return out
