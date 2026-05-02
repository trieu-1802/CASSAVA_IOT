"""FastAPI app — Z-score branch.

Registers ZScoreDetector + SeasonalZScoreDetector at startup, fitting both
from the last 30 days of Mongo data. /detect runs both in parallel and ORs
their verdicts.
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone

from fastapi import FastAPI, HTTPException

from ml.base import AnomalyDetector
from ml.data import load_sensor_series
from ml.seasonal_zscore import SeasonalZScoreDetector
from ml.zscore import ZScoreDetector
from .schemas import DetectRequest, DetectResponse, MethodVerdict, ModelInfo

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)

DETECTORS: dict[str, AnomalyDetector] = {}

# How far back to look when fitting at startup.
FIT_LOOKBACK_DAYS = 30


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        start = datetime.now(timezone.utc) - timedelta(days=FIT_LOOKBACK_DAYS)
        series = load_sensor_series(start=start)
        clean = series.dropna()
        logger.info("Loaded %d hourly points (last %d days) for fitting", len(clean), FIT_LOOKBACK_DAYS)

        if len(clean) >= 10:
            try:
                z = ZScoreDetector()
                z.fit(series)
                DETECTORS[z.name] = z
                logger.info("Fit ZScoreDetector (residual_std=%.3f)", z.residual_std or 0)
            except Exception as e:
                logger.warning("ZScoreDetector fit failed: %s", e)

            try:
                s = SeasonalZScoreDetector()
                s.fit(series)
                DETECTORS[s.name] = s
                logger.info("Fit SeasonalZScoreDetector (%d hour buckets)", len(s._bucket_stats))
            except Exception as e:
                logger.warning("SeasonalZScoreDetector fit failed: %s", e)
        else:
            logger.warning("Insufficient data (%d points) — no detectors fit", len(clean))
    except Exception:
        logger.exception("Startup fit pipeline failed")

    logger.info("ml-service starting; %d detectors loaded", len(DETECTORS))
    yield
    logger.info("ml-service shutting down")


app = FastAPI(title="Cassava ML Service", version="0.1.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "detectors_loaded": list(DETECTORS.keys())}


@app.get("/models", response_model=list[ModelInfo])
def list_models() -> list[ModelInfo]:
    return [
        ModelInfo(name=name, loaded=True, residual_std=det.residual_std)
        for name, det in DETECTORS.items()
    ]


@app.post("/detect", response_model=DetectResponse)
def detect(req: DetectRequest) -> DetectResponse:
    if not DETECTORS:
        raise HTTPException(503, "No detectors registered (branch may not have implemented this yet)")

    import pandas as pd

    ts = pd.Timestamp(req.time)
    verdicts: list[MethodVerdict] = []
    any_anomaly = False
    for name, det in DETECTORS.items():
        try:
            result = det.score(req.value, ts, k=3.0)
            verdicts.append(
                MethodVerdict(
                    name=name,
                    predicted=result.predicted,
                    residual=result.residual,
                    score=result.score,
                    is_anomaly=result.is_anomaly,
                )
            )
            any_anomaly = any_anomaly or result.is_anomaly
        except Exception as e:
            logger.exception("Detector %s failed", name)
            verdicts.append(MethodVerdict(name=name, score=0.0, is_anomaly=False))

    return DetectResponse(
        time=req.time,
        actual=req.value,
        is_anomaly=any_anomaly,
        methods=verdicts,
    )
