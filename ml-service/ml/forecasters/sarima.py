"""SARIMA(p,d,q)(P,D,Q,s) forecaster — built on `statsforecast`.

Replaces the older `statsmodels.SARIMAX` implementation, which allocates a
(state_dim^2, T) Kalman covariance array during every fit and OOMs on
multi-year hourly data with seasonal=24. statsforecast's Cython ARIMA fits
the same problem in tens of MB.

Two modes:
  - `auto=False` (default): fixed `order` and `seasonal_order` -- fits via
    `statsforecast.models.ARIMA`.
  - `auto=True`: stepwise AIC search via `statsforecast.models.AutoARIMA`.
    The chosen `(p,d,q)(P,D,Q,m)` is recorded back onto `self.order` /
    `self.seasonal_order` after fit.

Online forecasting: `update(value, time)` extends model state via the
underlying model's `forward()` without refitting.
"""
from __future__ import annotations

import pickle
from pathlib import Path

import numpy as np
import pandas as pd

from ..base import ForecastResult, Forecaster


class SarimaForecaster(Forecaster):
    name = "sarima"

    def __init__(
        self,
        order: tuple[int, int, int] = (1, 1, 1),
        seasonal_order: tuple[int, int, int, int] = (1, 1, 1, 24),
        auto: bool = False,
    ) -> None:
        super().__init__()
        self.order = order
        self.seasonal_order = seasonal_order
        self.auto = auto
        self._model = None  # statsforecast ARIMA or AutoARIMA after fit

    def fit(self, series: pd.Series) -> None:
        from statsforecast.models import ARIMA, AutoARIMA  # noqa: PLC0415

        clean = series.interpolate(limit=3).dropna()
        s = self.seasonal_order[3]
        if len(clean) < 3 * s:
            raise ValueError(f"SARIMA(s={s}) needs >= {3 * s} points, got {len(clean)}")
        y = clean.values.astype(np.float64)

        if self.auto:
            print(f"  [SARIMA] AutoARIMA stepwise search (season_length={s})...")
            model = AutoARIMA(
                season_length=s,
                max_p=2, max_q=2,
                max_P=1, max_Q=1,
                max_d=1, max_D=1,
                stepwise=True,
                trace=True,
                approximation=False,
            )
            model.fit(y)
            arma = model.model_["arma"]
            p, q, P, Q, m, d, D = (int(x) for x in arma)
            self.order = (p, d, q)
            self.seasonal_order = (P, D, Q, m)
            print(f"  [SARIMA] Auto-selected order: {self.order}{self.seasonal_order}")
        else:
            p, d, q = self.order
            P, D, Q, m = self.seasonal_order
            model = ARIMA(
                order=(p, d, q),
                seasonal_order=(P, D, Q),
                season_length=m,
            )
            model.fit(y)

        self._model = model
        self.fitted = True

        residuals = np.asarray(model.model_["residuals"])
        residuals = residuals[~np.isnan(residuals)]
        self._calibrate_residual_std(pd.Series(residuals))

    def predict(self, time: pd.Timestamp, horizon: int = 1) -> ForecastResult:
        if self._model is None:
            raise RuntimeError("SarimaForecaster not fitted")
        out = self._model.predict(h=horizon)
        values = [float(v) for v in out["mean"]]
        ts = pd.date_range(pd.Timestamp(time), periods=horizon, freq="h")
        return ForecastResult(
            forecast=values,
            timestamps=list(ts),
            name=self.name,
            residual_std=self.residual_std,
        )

    def update(self, value: float, time: pd.Timestamp) -> None:
        if self._model is None:
            return
        try:
            self._model.forward(np.array([value], dtype=np.float64))
        except Exception:
            pass

    def save(self, path: Path) -> None:
        with open(path, "wb") as f:
            pickle.dump(
                {
                    "order": self.order,
                    "seasonal_order": self.seasonal_order,
                    "model": self._model,
                    "residual_std": self.residual_std,
                },
                f,
            )

    def load(self, path: Path) -> None:
        with open(path, "rb") as f:
            d = pickle.load(f)
        self.order = d["order"]
        self.seasonal_order = d["seasonal_order"]
        self._model = d["model"]
        self.residual_std = d["residual_std"]
        self.fitted = True
