"""FastAPI app — ML branch.

Loads ARIMA, SARIMA, LSTM artifacts (if they exist) at startup. Models are
trained offline via `python -m scripts.train --model {arima,sarima,lstm,all}`.

/detect runs all loaded detectors in parallel and ORs their verdicts.
"""
from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from ml.arima_model import ArimaDetector
from ml.base import AnomalyDetector
from ml.config import ARTIFACTS_DIR
from ml.lstm_model import LstmDetector
from ml.sarima_model import SarimaDetector
from .schemas import DetectRequest, DetectResponse, MethodVerdict, ModelInfo

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)

DETECTORS: dict[str, AnomalyDetector] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    # (DetectorClass, artifact_path) — pkl for ARIMA/SARIMA, dir for LSTM.
    artifacts = [
        (ArimaDetector, ARTIFACTS_DIR / "arima.pkl"),
        (SarimaDetector, ARTIFACTS_DIR / "sarima.pkl"),
        (LstmDetector, ARTIFACTS_DIR / "lstm"),
    ]

    for cls, path in artifacts:
        if not path.exists():
            logger.info("Skipping %s — no artifact at %s", cls.__name__, path)
            continue
        try:
            det = cls()
            det.load(path)
            DETECTORS[det.name] = det
            logger.info("Loaded %s (residual_std=%.3f)", det.name, det.residual_std or 0)
        except Exception as e:
            logger.warning("Failed to load %s: %s", cls.__name__, e)

    if not DETECTORS:
        logger.warning(
            "No models loaded. Train via: python -m scripts.train --model all"
        )

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
