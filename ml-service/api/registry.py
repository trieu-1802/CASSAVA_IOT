"""In-process registry of loaded detectors and forecasters.

Populated by `api.main.lifespan` at startup; consumed by the route modules.
Two dicts keyed by name keep the two roles separate so each endpoint only
sees the models it should run.
"""
from __future__ import annotations

from ml.base import AnomalyDetector, Forecaster

DETECTORS: dict[str, AnomalyDetector] = {}
FORECASTERS: dict[str, Forecaster] = {}
