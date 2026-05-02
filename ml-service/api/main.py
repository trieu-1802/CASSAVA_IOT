"""FastAPI app skeleton.

Detection endpoints are implemented per branch:
- feat/anomaly-zscore: registers ZScoreDetector + SeasonalZScoreDetector
- feat/anomaly-ml: registers ArimaDetector + SarimaDetector + LstmDetector

This base scaffold leaves DETECTORS empty; /detect returns 503 until a branch
populates it.
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from ml.base import AnomalyDetector
from .schemas import DetectRequest, DetectResponse, MethodVerdict, ModelInfo

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)

# Branch-specific code populates this dict in its lifespan/startup.
DETECTORS: dict[str, AnomalyDetector] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Branches override this by importing main and adding to DETECTORS at startup.
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
