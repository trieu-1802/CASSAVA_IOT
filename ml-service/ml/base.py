"""Abstract base for anomaly detectors.

Each detector wraps a different method (statistical, ML forecasting) but exposes
a uniform interface so the API and evaluation harness can treat them generically.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import pandas as pd


@dataclass
class ScoreResult:
    actual: float
    predicted: Optional[float]
    residual: Optional[float]
    score: float  # |z|-like score; > k → anomaly
    is_anomaly: bool
    method: str


class AnomalyDetector(ABC):
    """Forecast-based or statistical anomaly detector."""

    name: str = "base"

    def __init__(self) -> None:
        self.residual_std: float | None = None
        self.fitted: bool = False

    @abstractmethod
    def fit(self, series: pd.Series) -> None:
        """Train on a historical series."""

    @abstractmethod
    def score(self, actual: float, time: pd.Timestamp, k: float = 3.0) -> ScoreResult:
        """Score one observation at `time` with value `actual`."""

    @abstractmethod
    def save(self, path: Path) -> None: ...

    @abstractmethod
    def load(self, path: Path) -> None: ...

    def _calibrate_residual_std(self, residuals: pd.Series) -> None:
        clean = residuals.dropna()
        if len(clean) < 2:
            raise ValueError("Need at least 2 residuals to calibrate σ")
        self.residual_std = float(clean.std())
