"""ARIMA(p,d,q) anomaly detector — univariate hourly temperature.

Forecasting-based anomaly detection: model predicts the next hourly value;
large residual (|actual - predicted| > k·σ) is flagged as anomaly.

State is updated on each `score()` call via `ARIMAResults.append(refit=False)`,
so successive calls behave like an online forecaster — each prediction uses
the full history of all previously-scored observations.
"""
from __future__ import annotations

import pickle
from pathlib import Path

import pandas as pd
from statsmodels.tsa.arima.model import ARIMA

from .base import AnomalyDetector, ScoreResult


class ArimaDetector(AnomalyDetector):
    name = "arima"

    def __init__(self, order: tuple[int, int, int] = (2, 1, 2)) -> None:
        super().__init__()
        self.order = order
        self._results = None  # statsmodels ARIMAResults

    def fit(self, series: pd.Series) -> None:
        clean = series.interpolate(limit=3).dropna()
        if len(clean) < 30:
            raise ValueError(f"ARIMA needs ≥ 30 points, got {len(clean)}")
        # ARIMA on hourly grid; suppress convergence warnings for MVP
        self._results = ARIMA(clean, order=self.order).fit()
        self.fitted = True
        residuals = clean - self._results.fittedvalues
        self._calibrate_residual_std(residuals)

    def score(self, actual: float, time: pd.Timestamp, k: float = 3.0) -> ScoreResult:
        if self._results is None:
            return ScoreResult(actual, None, None, 0.0, False, self.name)

        try:
            forecast = float(self._results.forecast(steps=1).iloc[0])
        except Exception:
            return ScoreResult(actual, None, None, 0.0, False, self.name)

        residual = actual - forecast
        sigma = self.residual_std or 1.0
        z = abs(residual) / sigma
        is_anom = z > k

        # Extend the model state with the new observation so next forecast uses it
        try:
            new_obs = pd.Series([actual], index=pd.DatetimeIndex([pd.Timestamp(time)]))
            self._results = self._results.append(new_obs, refit=False)
        except Exception:
            pass

        return ScoreResult(actual, forecast, residual, z, is_anom, self.name)

    def save(self, path: Path) -> None:
        with open(path, "wb") as f:
            pickle.dump(
                {
                    "order": self.order,
                    "results": self._results,
                    "residual_std": self.residual_std,
                },
                f,
            )

    def load(self, path: Path) -> None:
        with open(path, "rb") as f:
            d = pickle.load(f)
        self.order = d["order"]
        self._results = d["results"]
        self.residual_std = d["residual_std"]
        self.fitted = True
