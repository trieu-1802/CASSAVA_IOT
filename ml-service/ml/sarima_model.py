"""SARIMA(p,d,q)(P,D,Q,s) anomaly detector — univariate hourly temperature.

Same forecasting-residual mechanic as ArimaDetector but with seasonal terms.
Default seasonal_order=(1,1,1,24) captures the daily cycle (s=24 hours).

Needs at least 3 full seasonal cycles (≥ 72 hourly points) for meaningful
seasonal terms.
"""
from __future__ import annotations

import pickle
import warnings
from pathlib import Path

import pandas as pd
from statsmodels.tsa.statespace.sarimax import SARIMAX

from .base import AnomalyDetector, ScoreResult


class SarimaDetector(AnomalyDetector):
    name = "sarima"

    def __init__(
        self,
        order: tuple[int, int, int] = (1, 1, 1),
        seasonal_order: tuple[int, int, int, int] = (1, 1, 1, 24),
    ) -> None:
        super().__init__()
        self.order = order
        self.seasonal_order = seasonal_order
        self._results = None

    def fit(self, series: pd.Series) -> None:
        clean = series.interpolate(limit=3).dropna()
        s = self.seasonal_order[3]
        if len(clean) < 3 * s:
            raise ValueError(
                f"SARIMA(s={s}) needs ≥ {3 * s} points, got {len(clean)}"
            )
        # MLE convergence on small samples often warns harmlessly; quiet for MVP
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            self._results = SARIMAX(
                clean,
                order=self.order,
                seasonal_order=self.seasonal_order,
                enforce_stationarity=False,
                enforce_invertibility=False,
            ).fit(disp=False)
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
                    "seasonal_order": self.seasonal_order,
                    "results": self._results,
                    "residual_std": self.residual_std,
                },
                f,
            )

    def load(self, path: Path) -> None:
        with open(path, "rb") as f:
            d = pickle.load(f)
        self.order = d["order"]
        self.seasonal_order = d["seasonal_order"]
        self._results = d["results"]
        self.residual_std = d["residual_std"]
        self.fitted = True
