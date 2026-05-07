"""ARIMA(p,d,q) forecaster — univariate hourly series.

Online forecasting: `update(value, time)` extends the fitted state via
`ARIMAResults.append(refit=False)`, so successive `predict(h=1)` calls use
the full history of all previously-seen observations without re-doing MLE.

To turn this into an anomaly detector, wrap with
`ml.detectors.ResidualDetector(ArimaForecaster())`.
"""
from __future__ import annotations

import pickle
from pathlib import Path

import pandas as pd
from statsmodels.tsa.arima.model import ARIMA

from ..base import ForecastResult, Forecaster


class ArimaForecaster(Forecaster):
    name = "arima"

    def __init__(self, order: tuple[int, int, int] = (2, 1, 2)) -> None:
        super().__init__()
        self.order = order
        self._results = None  # statsmodels ARIMAResults

    def fit(self, series: pd.Series) -> None:
        clean = series.interpolate(limit=3).dropna()
        if len(clean) < 30:
            raise ValueError(f"ARIMA needs >= 30 points, got {len(clean)}")
        self._results = ARIMA(clean, order=self.order).fit()
        self.fitted = True
        residuals = clean - self._results.fittedvalues
        self._calibrate_residual_std(residuals)

    def predict(self, time: pd.Timestamp, horizon: int = 1) -> ForecastResult:
        if self._results is None:
            raise RuntimeError("ArimaForecaster not fitted")
        forecast = self._results.forecast(steps=horizon)
        ts = pd.date_range(pd.Timestamp(time), periods=horizon, freq="h")
        return ForecastResult(
            forecast=[float(v) for v in forecast.values],
            timestamps=list(ts),
            name=self.name,
            residual_std=self.residual_std,
        )

    def update(self, value: float, time: pd.Timestamp) -> None:
        if self._results is None:
            return
        try:
            new_obs = pd.Series([value], index=pd.DatetimeIndex([pd.Timestamp(time)]))
            self._results = self._results.append(new_obs, refit=False)
        except Exception:
            pass

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
