"""LSTM forecaster — multivariate, trained on NASA POWER hourly data.

Input: 48-hour window of (temperature, relativeHumidity, wind, rain, radiation).
Output: predicted next-hour temperature.

Training data comes from NASA POWER (3+ years), giving the model a broad
view of "typical" weather patterns. At prediction time, we query the last
48 hours of MQTT sensor data from Mongo and ask the LSTM to predict the
next hour's temperature.

Architecture (small on purpose -- limited training data):
    LSTM(64) -> Dropout(0.2) -> Dense(32, relu) -> Dense(1)

Multi-step horizons (`predict(time, h>1)`) are produced by iterative roll-forward:
each step's predicted temperature is fed back into the window with the other
features held at their last observed values. Crude but cheap; for richer
horizons retrain with seq2seq output.

To turn this into an anomaly detector, wrap with
`ml.detectors.ResidualDetector(LstmForecaster())`.
"""
from __future__ import annotations

import pickle
from datetime import timedelta
from pathlib import Path

import numpy as np
import pandas as pd

from ..base import ForecastResult, Forecaster
from ..data import load_sensor_series

LSTM_FEATURES = ["temperature", "relativeHumidity", "wind", "rain", "radiation"]
TARGET_FEATURE = "temperature"
TARGET_IDX = 0  # temperature is first in LSTM_FEATURES


class LstmForecaster(Forecaster):
    name = "lstm"

    def __init__(self, window: int = 48, epochs: int = 30, batch_size: int = 32) -> None:
        super().__init__()
        self.window = window
        self.epochs = epochs
        self.batch_size = batch_size
        self._scaler = None  # sklearn MinMaxScaler
        self._model = None   # Keras model
        # Offline-evaluation context: when set, predict() pulls the 48h window
        # from this DataFrame instead of querying Mongo. Used by the offline
        # evaluator that has no Mongo data flow.
        self._offline_context: pd.DataFrame | None = None

    def set_offline_context(self, multivariate_df: pd.DataFrame) -> None:
        """Override the Mongo-based context loader with an in-memory frame.

        The frame must have all `LSTM_FEATURES` columns and a sorted DatetimeIndex.
        Each `predict(time, ...)` call will then look up the 48 hours ending just
        before `time` from this frame instead of hitting Mongo.
        """
        self._offline_context = multivariate_df

    # ---- training (NASA multivariate) ------------------------------------

    def fit(self, multivariate_df: pd.DataFrame) -> None:
        """Train on a NASA-style multivariate hourly DataFrame."""
        from sklearn.preprocessing import MinMaxScaler  # noqa: PLC0415
        from tensorflow import keras  # noqa: PLC0415

        df = multivariate_df.reindex(columns=LSTM_FEATURES).interpolate(limit=3).dropna()
        if len(df) < self.window + 50:
            raise ValueError(f"LSTM needs >= {self.window + 50} rows, got {len(df)}")

        self._scaler = MinMaxScaler()
        scaled = self._scaler.fit_transform(df.values)

        X, y = self._make_sequences(scaled)

        self._model = keras.Sequential(
            [
                keras.layers.Input(shape=(self.window, len(LSTM_FEATURES))),
                keras.layers.LSTM(64, return_sequences=False),
                keras.layers.Dropout(0.2),
                keras.layers.Dense(32, activation="relu"),
                keras.layers.Dense(1),
            ]
        )
        self._model.compile(optimizer="adam", loss="mse")
        self._model.fit(X, y, epochs=self.epochs, batch_size=self.batch_size, verbose=0)
        self.fitted = True

        yhat_scaled = self._model.predict(X, verbose=0).flatten()
        predicted_temp = self._inverse_target(yhat_scaled)
        actual_temp = df[TARGET_FEATURE].iloc[self.window :].values
        residuals = pd.Series(actual_temp - predicted_temp)
        self._calibrate_residual_std(residuals)

    def _make_sequences(self, scaled: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        X, y = [], []
        for i in range(len(scaled) - self.window):
            X.append(scaled[i : i + self.window])
            y.append(scaled[i + self.window, TARGET_IDX])
        return np.array(X), np.array(y)

    def _inverse_target(self, scaled_y: np.ndarray) -> np.ndarray:
        """Inverse-transform a scaled target column back to the original units.
        Scaler was fit on all features, so we pad the others with zeros.
        """
        pad = np.zeros((len(scaled_y), len(LSTM_FEATURES)))
        pad[:, TARGET_IDX] = scaled_y
        return self._scaler.inverse_transform(pad)[:, TARGET_IDX]

    # ---- prediction ------------------------------------------------------

    def predict(self, time: pd.Timestamp, horizon: int = 1) -> ForecastResult:
        if self._model is None or self._scaler is None:
            raise RuntimeError("LstmForecaster not fitted")

        ts = pd.Timestamp(time)
        context = self._load_recent_context(ts)
        if len(context) < self.window:
            raise RuntimeError(f"Need {self.window} hours of context, got {len(context)}")

        # Iterative roll-forward for horizon > 1: predict t+1, append (with
        # other features held), predict t+2, etc.
        scaled_window = self._scaler.transform(context.values).copy()
        forecasts: list[float] = []
        for _ in range(horizon):
            X = scaled_window.reshape(1, self.window, len(LSTM_FEATURES))
            yhat_scaled = float(self._model.predict(X, verbose=0)[0, 0])
            predicted = float(self._inverse_target(np.array([yhat_scaled]))[0])
            forecasts.append(predicted)

            # Roll the window: drop oldest, append [yhat_scaled, last-features-unchanged]
            next_row = scaled_window[-1].copy()
            next_row[TARGET_IDX] = yhat_scaled
            scaled_window = np.vstack([scaled_window[1:], next_row])

        out_ts = pd.date_range(ts, periods=horizon, freq="h")
        return ForecastResult(
            forecast=forecasts,
            timestamps=list(out_ts),
            name=self.name,
            residual_std=self.residual_std,
        )

    # No `update()` override needed: LSTM rebuilds its 48h window on each
    # predict() call from `_load_recent_context`, so newly-arrived points
    # (live Mongo or appended offline frame) are picked up automatically.

    def _load_recent_context(self, end_time: pd.Timestamp) -> pd.DataFrame:
        """Pull the last `window` hours of multivariate data ending just before
        `end_time`. Uses `_offline_context` if set; otherwise queries Mongo.
        """
        if self._offline_context is not None:
            df = self._offline_context
            df = df.loc[:end_time].iloc[:-1] if end_time in df.index else df.loc[:end_time]
            df = df.reindex(columns=LSTM_FEATURES).interpolate(limit=3).dropna()
            return df.tail(self.window)

        start = (end_time - timedelta(hours=self.window + 5)).to_pydatetime()
        end = end_time.to_pydatetime()
        cols: dict[str, pd.Series] = {}
        for sensor in LSTM_FEATURES:
            cols[sensor] = load_sensor_series(sensor_id=sensor, start=start, end=end)
        df = pd.DataFrame(cols).interpolate(limit=3).dropna()
        return df.tail(self.window)

    # ---- persistence -----------------------------------------------------

    def save(self, path: Path) -> None:
        path.mkdir(parents=True, exist_ok=True)
        self._model.save(path / "model.keras")
        with open(path / "meta.pkl", "wb") as f:
            pickle.dump(
                {
                    "window": self.window,
                    "epochs": self.epochs,
                    "batch_size": self.batch_size,
                    "scaler": self._scaler,
                    "residual_std": self.residual_std,
                },
                f,
            )

    def load(self, path: Path) -> None:
        from tensorflow import keras  # noqa: PLC0415

        self._model = keras.models.load_model(path / "model.keras")
        with open(path / "meta.pkl", "rb") as f:
            d = pickle.load(f)
        self.window = d["window"]
        self.epochs = d["epochs"]
        self.batch_size = d["batch_size"]
        self._scaler = d["scaler"]
        self.residual_std = d["residual_std"]
        self.fitted = True
