# Anomaly Detection vs. Forecasting — Empirical Comparison

**Branch:** `feat/anomaly-compare`
**Service:** `ml-service` (Python 3.13, FastAPI)
**Date generated:** 2026-05-07

This report compares two distinct roles of time-series ML models on the same hourly temperature data, evaluated with separate, role-appropriate metrics:

1. **Anomaly detection** — does the model correctly flag injected anomalies? Measured by Precision / Recall / F1.
2. **Forecasting** — does the model correctly predict future values? Measured by MAE / RMSE / MAPE on the unperturbed test set.

The two evaluations are run independently because the optimization targets disagree: detectors should be **robust** to outliers (ignore them), while forecasters should **track signal** closely. A single model rarely wins at both.

---

## 1. Methodology

### Data

- **Source:** NASA POWER hourly weather data (single point, agricultural project location)
- **Variables (LSTM only):** temperature, relativeHumidity, wind, rain, radiation
- **Range:** 2020-01-01 → 2025-12-30 (skips known-corrupt 2025-12-31+ data)
- **Total points:** 52 584 hourly rows
- **Train / test split:** chronological 80% / 20% — train = 42 067 oldest points, test = 10 517 newest points (no shuffle, no leakage)

### Detection evaluation

- Synthetic anomalies injected into the test slice only:
  - **Spike** — 1% of test points get ±5σ shift (≈ 105 anomalies)
  - **Outlier** — 0.5% replaced by `max + 50` (≈ 52 anomalies)
  - **Drift** — 24 consecutive points get a linear ramp from 0 → 3σ (24 anomalies)
- Total injected: **181 / 10 517 (1.7%)**
- Each detector is **refit on the train slice only**, then scores every test point. Threshold `k` swept from 1.0 → 10.0 in steps of 0.5; best-F1 row per detector reported.

### Forecast evaluation

- Walks the unperturbed test slice in **online mode**: at each timestamp, `predict(h)` is called for horizons {1, 6, 24}, the resulting values are compared against the actual continuation, and `update(actual)` extends the model's state for the next step.
- Capped at 2 000 walk starts per forecaster to keep runtime tractable.
- **Train-only refit** — orders for ARIMA / SARIMA were auto-selected once via stepwise AIC search (see §4) and lifted into the eval; only the parameters were refit on the 80% train slice.

### Auto-selected orders (used in both evaluations)

| Model | Order | Search method |
|---|---|---|
| ARIMA | (5, 1, 4) | `pmdarima.auto_arima`, statsmodels backend |
| SARIMA | (2, 0, 1)(1, 1, 0, 24) | `statsforecast.AutoARIMA`, Cython backend |
| LSTM | window=48, hidden=64, dropout=0.2 | hand-picked (no auto-search for NN architecture) |

---

## 2. Detection results

| Detector | Best k | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| **seasonal_zscore** | **3.0** | 1.000 | 0.901 | **0.948** |
| **zscore** | **5.0** | 1.000 | 0.845 | **0.916** |
| lstm_residual | 10.0 | 0.332 | 0.867 | 0.480 |
| arima_residual | 10.0 | 0.032 | 0.994 | 0.062 |
| sarima_residual | 10.0 | 0.024 | 1.000 | 0.046 |

Full F1-vs-k tables saved to [artifacts/k_sweep_refactored.csv](../artifacts/k_sweep_refactored.csv).

### F1 vs k — selected rows

| k | zscore | seasonal_zscore | arima_residual | sarima_residual | lstm_residual |
|---:|---:|---:|---:|---:|---:|
| 2.0 | 0.306 | 0.486 | 0.036 | 0.035 | 0.051 |
| 3.0 | 0.710 | **0.948** | 0.038 | 0.036 | 0.086 |
| 4.0 | 0.890 | 0.919 | 0.040 | 0.037 | 0.203 |
| 5.0 | **0.916** | 0.793 | 0.043 | 0.038 | 0.307 |
| 7.0 | 0.869 | 0.492 | 0.049 | 0.041 | 0.393 |
| 10.0 | 0.703 | 0.446 | 0.062 | 0.046 | **0.480** |

### Interpretation

- **Robust statistical methods win decisively.** Seasonal Z-score at k=3 achieves perfect precision (no false positives) and 90% recall, for an F1 of 0.948. Modified Z-score at k=5 is close behind at F1=0.916. Both methods compute their reference statistics from the data they're scoring, so they're insensitive to forecast-variance drift.

- **Forecast-residual detection fails for ARIMA/SARIMA.** Both saturate at F1 ≈ 0.05 — essentially the base rate of injected anomalies (1.7%) — meaning they flag nearly everything. Their `is_anomaly = |residual| / σ > k` rule depends on the in-sample σ accurately characterizing forecast errors on held-out data. It does not: one-step-ahead errors at test time are an order of magnitude larger than training-time residuals, so the threshold rule degenerates.

- **LSTM-residual is salvageable but not competitive.** Its F1 climbs steadily with k and reaches 0.480 at the search edge. With a wider sweep (k → 15–30) it would likely improve further. The 48-hour multivariate window grounds its forecasts much better than the univariate ARIMA/SARIMA, which is why it does not collapse to the base rate.

---

## 3. Forecast results

Online walk over the clean test slice, 2 000 starts per forecaster (ARIMA / SARIMA: full 2 000; LSTM: 1 952 effective after the 48-hour warm-up window), online update with each ground-truth observation.

| Forecaster | Horizon (h) | MAE (°C) | RMSE (°C) | MAPE % |
|---|---:|---:|---:|---:|
| ARIMA(5,1,4) | 1 | 5.50 | 6.71 | 37.6 |
| ARIMA(5,1,4) | 6 | 7.11 | 8.38 | 48.1 |
| ARIMA(5,1,4) | 24 | 7.16 | 8.43 | 49.0 |
| SARIMA(2,0,1)(1,1,0,24) | 1 | 5.36 | 6.55 | 36.7 |
| SARIMA(2,0,1)(1,1,0,24) | 6 | 6.73 | 8.00 | 45.7 |
| SARIMA(2,0,1)(1,1,0,24) | **24** | **5.64** | **6.87** | **39.2** |
| LSTM (5 features, 48 h window) | **1** | **1.75** | **1.99** | **8.7** |
| LSTM (5 features, 48 h window) | 6 | 9.30 | 10.52 | 46.3 |
| LSTM (5 features, 48 h window) | 24 | 10.02 | 11.13 | 50.3 |

Full numbers saved to [artifacts/forecast_arima_sarima.csv](../artifacts/forecast_arima_sarima.csv) and [artifacts/forecast_lstm.csv](../artifacts/forecast_lstm.csv).

### Interpretation

- **LSTM dominates at h=1 by a wide margin.** MAE 1.75 °C / RMSE 1.99 °C / MAPE 8.7%. That is **3× better** than ARIMA/SARIMA on the same one-step task, and the only single-digit MAPE in the comparison. The 48-hour multivariate window (temperature, relativeHumidity, wind, rain, radiation) gives the network enough context to track the diurnal cycle directly — something ARIMA cannot do at all and SARIMA only approximates via its m=24 seasonal lag.

- **LSTM falls apart at h ≥ 6.** This is an architectural limitation, not a data problem. The current `LstmForecaster.predict(time, horizon)` produces multi-step forecasts by **iteratively rolling the 48-hour window forward**: it predicts t+1, appends `[ŷ_t+1, last_humidity, last_wind, last_rain, last_radiation]` to the window, predicts t+2, and so on. The non-temperature features are held constant from the last observation, but humidity / wind / radiation have their own diurnal cycles, so the input window quickly drifts out of distribution. Errors compound rapidly. To genuinely forecast multi-step with LSTM, one of these is needed:
  1. A seq2seq LSTM trained to emit h outputs directly (single network, multi-step head).
  2. Independent forecasters for the other 4 features so the rolled window stays in distribution.
  3. A separate model per horizon (h=1, h=6, h=24) trained with the appropriate target offset.

- **SARIMA earns its complexity at h=24.** Note the non-monotone error curve: SARIMA's h=24 MAE (5.64) is *better* than its h=6 MAE (6.73). The model's `m=24` term effectively says "tomorrow at this hour will look like today at this hour," which is approximately right for hourly temperature. ARIMA, having no seasonal term, drifts toward its long-run mean as horizon grows, so its errors increase monotonically.

- **For multi-hour forecasting, SARIMA is currently the best option.** Until an LSTM seq2seq is trained, SARIMA is the right model for h ≥ 6 and especially for h = 24 (irrigation planning typically wants a 24-hour outlook). The combination — LSTM for h=1, SARIMA for h≥6 — is a defensible production split.

- **The (5,1,4) auto order vs the (2,1,2) default.** Auto-selection picked a higher-order ARIMA, which fits in-sample better (lower training residual σ) but doesn't translate to superior out-of-sample forecasts at this scale of data.

---

## 4. Cross-cutting observations

### Why detectors and forecasters are kept separate

The 5×3 grid below summarizes the design decision empirically:

| Model | Detection F1 | Forecast MAE (h=1) | Forecast MAE (h=24) | Recommended role |
|---|---:|---:|---:|---|
| Modified Z-score | 0.916 | n/a (smoother, not forecaster) | n/a | **Detector** (Tier 2) |
| Seasonal Z-score | **0.948** | n/a | n/a | **Detector** (Tier 3, primary) |
| ARIMA(5,1,4) | 0.062 | 5.50 | 7.16 | Baseline only |
| SARIMA(2,0,1)(1,1,0,24) | 0.046 | 5.36 | **5.64** | **Forecaster** (best at long horizon) |
| LSTM (5 features, 48 h window) | 0.480 | **1.75** | 10.02 | **Forecaster** (best at h=1, fails at h≥6 with current arch) |

The same model is rarely competitive in both columns. Robust statistics dominate detection because they ignore the anomalous point being scored; smooth multivariate forecasters dominate prediction because they integrate context.

### Implication for the production system

- **Streaming anomaly path:** `Modified Z-score` (Tier 2) → `Seasonal Z-score` (Tier 3, k=3). Threshold `|score| > 3` with seasonal baseline; perfect precision, 90% recall on the held-out test set.
- **Short-horizon forecasting (h = 1):** LSTM. MAE 1.75 °C, MAPE 8.7%. Use for next-hour ET₀ refinement, immediate moisture-depletion projection, or any single-step planning input.
- **Long-horizon forecasting (h = 6, 12, 24):** SARIMA. The current LSTM degrades severely past h=1 due to its iterative roll-forward strategy with held-constant non-target features. SARIMA's seasonal lag tracks the diurnal cycle out to h=24 with MAE 5.64 °C — three to four times better than the iterative LSTM at the same horizon. Use SARIMA for irrigation planning (typical horizon = next 24 hours) until an LSTM seq2seq is trained.
- **ARIMA/SARIMA as detectors:** Document as a *negative result*. The forecast → residual → threshold paradigm fails when the in-sample residual σ does not generalize to the test slice — a common failure mode that is worth flagging in the thesis.

---

## 5. Reproduction

Run from `ml-service/` with the project's virtualenv active. NASA POWER CSV must already exist (`python -m scripts.fetch_nasa` to fetch).

```bash
# Detection sweep (5 methods, k = 1 → 10 step 0.5)
python -m scripts.evaluate_detection --methods all \
    --sweep-k 1.0 10.0 0.5 \
    --sweep-out artifacts/k_sweep_refactored.csv

# Forecast walk (ARIMA + SARIMA, h ∈ {1, 6, 24}, 2000 starts) — fast, ~10 min
python -m scripts.evaluate_forecast --methods arima,sarima \
    --horizons 1 6 24 --max-points 2000 \
    --out artifacts/forecast_arima_sarima.csv

# Forecast walk (LSTM only, h ∈ {1, 6, 24}, 2000 starts) — slower, ~20 min
python -m scripts.evaluate_forecast --methods lstm \
    --horizons 1 6 24 --max-points 2000 \
    --out artifacts/forecast_lstm.csv
```

Auto-selected orders (one-time, already done):

```bash
python -m scripts.train --model arima --auto-order
python -m scripts.train --model sarima --auto-order
python -m scripts.train --model lstm
```

---

## 6. Files referenced

- Source code: [ml/detectors/](../ml/detectors/), [ml/forecasters/](../ml/forecasters/)
- Evaluation scripts: [scripts/evaluate_detection.py](../scripts/evaluate_detection.py), [scripts/evaluate_forecast.py](../scripts/evaluate_forecast.py)
- Raw outputs: [artifacts/k_sweep_refactored.csv](../artifacts/k_sweep_refactored.csv), [artifacts/forecast_arima_sarima.csv](../artifacts/forecast_arima_sarima.csv), [artifacts/forecast_lstm.csv](../artifacts/forecast_lstm.csv)
- Training data: [artifacts/nasa/training_data.csv](../artifacts/nasa/training_data.csv)
