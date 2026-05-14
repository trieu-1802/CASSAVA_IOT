"""FastAPI app -- detector + forecaster split.

Two roles, two endpoints, both per-sensor:
  - /detect   -> for each weather sensor: zscore + seasonal_zscore (always)
                 plus arima_residual / sarima_residual (when per-sensor artifact
                 exists) plus lstm_residual (temperature only).
  - /forecast -> per-sensor ARIMA / SARIMA / LSTM forecasters loaded from
                 `{model}_{sensor}.pkl` artifacts. LSTM is temperature-only.

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
from ml.detectors import ResidualDetector, SeasonalZScoreDetector, ZScoreDetector
from ml.forecasters import ArimaForecaster, LstmForecaster, SarimaForecaster

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)

NASA_CSV_PATH = ARTIFACTS_DIR / "nasa" / "training_data.csv"
# Legacy temperature-only LSTM artifact. Loaded as a fallback for the
# temperature sensor when no `lstm_temperature/` per-sensor artifact exists.
LEGACY_LSTM_PATH = ARTIFACTS_DIR / "lstm"

# Weather sensors that get per-sensor models at startup. Must match the
# canonical sensor ids resolved by the BE's MqttSensorTopics.
WEATHER_SENSORS = ["temperature", "relativeHumidity", "rain", "radiation", "wind"]

# Statistical detectors fit fresh from the NASA CSV for every sensor.
LIVE_STATISTICAL_DETECTORS: list[tuple[str, type[AnomalyDetector]]] = [
    ("zscore", ZScoreDetector),
    ("seasonal_zscore", SeasonalZScoreDetector),
]

# Per-sensor forecasters loaded from `{model}_{sensor}.pkl`. Each one is
# both exposed via /forecast and wrapped as a ResidualDetector for /detect.
PER_SENSOR_FORECASTERS: list[tuple[str, type[Forecaster]]] = [
    ("arima", ArimaForecaster),
    ("sarima", SarimaForecaster),
]


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
        logger.info(
            "Fit detector %s on %d points (residual_std=%s)", name, len(series), det.residual_std
        )
        return det
    except Exception:
        logger.exception("Failed to fit %s", name)
        return None


def _register_sensor(sensor_id: str, nasa_df: pd.DataFrame | None) -> None:
    """Populate DETECTORS[sensor_id] and FORECASTERS[sensor_id]."""
    detectors: dict[str, AnomalyDetector] = {}
    forecasters: dict[str, Forecaster] = {}

    # zscore + seasonal_zscore from the NASA CSV column.
    if nasa_df is not None and sensor_id in nasa_df.columns:
        series = nasa_df[sensor_id].dropna()
        for det_name, det_cls in LIVE_STATISTICAL_DETECTORS:
            det = _fit_detector(f"{det_name}/{sensor_id}", det_cls, series)
            if det is not None:
                detectors[det_name] = det

    # ARIMA + SARIMA per-sensor artifacts → both /forecast and residual /detect.
    for fc_name, fc_cls in PER_SENSOR_FORECASTERS:
        path = ARTIFACTS_DIR / f"{fc_name}_{sensor_id}.pkl"
        fc = _load_forecaster(f"{fc_name}/{sensor_id}", fc_cls, path)
        if fc is not None:
            forecasters[fc_name] = fc
            detectors[f"{fc_name}_residual"] = ResidualDetector(fc, name=f"{fc_name}_residual")

    # LSTM is multivariate; one artifact per target sensor at lstm_<sensor>/.
    # For temperature only, fall back to the legacy `lstm/` path if the
    # per-sensor artifact doesn't exist yet.
    lstm_path = ARTIFACTS_DIR / f"lstm_{sensor_id}"
    if not lstm_path.exists() and sensor_id == "temperature":
        lstm_path = LEGACY_LSTM_PATH
    lstm = _load_forecaster(f"lstm/{sensor_id}", LstmForecaster, lstm_path)
    if lstm is not None:
        forecasters["lstm"] = lstm
        detectors["lstm_residual"] = ResidualDetector(lstm, name="lstm_residual")

    if detectors:
        DETECTORS[sensor_id] = detectors
    if forecasters:
        FORECASTERS[sensor_id] = forecasters


@asynccontextmanager
async def lifespan(app: FastAPI):
    nasa_df: pd.DataFrame | None = None
    if NASA_CSV_PATH.exists():
        try:
            nasa_df = pd.read_csv(NASA_CSV_PATH, index_col="time", parse_dates=["time"])
        except Exception:
            logger.exception("Failed to load NASA CSV at %s -- statistical detectors will be skipped", NASA_CSV_PATH)
            nasa_df = None
    else:
        logger.warning(
            "NASA CSV missing at %s -- statistical detectors will not fit. "
            "Run `python -m scripts.fetch_nasa` first.",
            NASA_CSV_PATH,
        )

    for sensor_id in WEATHER_SENSORS:
        _register_sensor(sensor_id, nasa_df)

    logger.info(
        "ml-service starting; detectors=%s, forecasters=%s",
        {s: list(d.keys()) for s, d in DETECTORS.items()},
        {s: list(f.keys()) for s, f in FORECASTERS.items()},
    )
    yield
    logger.info("ml-service shutting down")


app = FastAPI(title="Cassava ML Service", version="0.5.0", lifespan=lifespan)
app.include_router(detect_router)
app.include_router(forecast_router)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "detectors": {s: list(d.keys()) for s, d in DETECTORS.items()},
        "forecasters": {s: list(f.keys()) for s, f in FORECASTERS.items()},
    }


@app.get("/models", response_model=list[ModelInfo])
def list_models() -> list[ModelInfo]:
    out: list[ModelInfo] = []
    for sensor_id, dets in DETECTORS.items():
        for name, det in dets.items():
            out.append(
                ModelInfo(
                    name=f"{name}/{sensor_id}",
                    role="detector",
                    loaded=True,
                    residual_std=det.residual_std,
                )
            )
    for sensor_id, fcs in FORECASTERS.items():
        for name, fc in fcs.items():
            out.append(
                ModelInfo(
                    name=f"{name}/{sensor_id}",
                    role="forecaster",
                    loaded=True,
                    residual_std=fc.residual_std,
                )
            )
    return out
