# ml-service

Python service for CASSAVA_IOT. Two roles:

- **Anomaly detection** — robust statistical methods (Modified Z-score, Seasonal Z-score) flag bad points on the streaming sensor path.
- **Forecasting** — ARIMA / SARIMA / LSTM predict the next `h` hourly values for irrigation planning.

These are deliberately separate. A model that's good at one is rarely the best at the other: detectors want robustness to outliers, forecasters want signal-tracking. See [docs/ml-branch.md](docs/ml-branch.md) for the empirical comparison.

```
ml/
├── base.py              # AnomalyDetector ABC + Forecaster ABC
├── detectors/
│   ├── zscore.py            # Modified Z-score (sliding MAD)
│   ├── seasonal_zscore.py   # hour-of-day baseline
│   └── residual.py          # wraps a Forecaster as a Detector
└── forecasters/
    ├── arima.py
    ├── sarima.py
    └── lstm.py              # multivariate (5 features, 48h window)

api/
├── main.py                  # mounts both routers
├── schemas.py
├── registry.py              # in-process model registry
└── routes/
    ├── detect.py            # POST /detect
    └── forecast.py          # POST /forecast

scripts/
├── fetch_nasa.py            # ONE-TIME: pull NASA POWER CSV
├── train.py                 # train forecasters from CSV
├── evaluate_detection.py    # P/R/F1 vs k, with anomaly injection
└── evaluate_forecast.py     # MAE/RMSE/MAPE per horizon on clean test data
```

## Branch context

This branch (`feat/anomaly-compare`) is the empirical-comparison playground. It carries both the detection and forecasting halves under one tree. The pure branches (`feat/anomaly-zscore`, `feat/anomaly-ml`) stay as canonical references for the thesis.

## Setup

Python 3.13.

```bash
cd ml-service
python -m venv .venv
source .venv/Scripts/activate   # Windows Git Bash
# .venv\Scripts\activate        # Windows cmd / PowerShell
# source .venv/bin/activate     # Linux / macOS

pip install -r requirements.txt
cp .env.example .env
```

## Training (forecasters only)

Two-step workflow: fetch NASA POWER once into a CSV, then train forecasters from it. The detectors don't need training — they fit at API startup from the same CSV.

```bash
# 1. ONE TIME: fetch NASA POWER -> artifacts/nasa/training_data.csv
#    Default range: 2020-01-01 -> 2025-12-30 (skips known-corrupt 2025-12-31+ data)
python -m scripts.fetch_nasa

# 2. Train forecasters
python -m scripts.train --model arima                 # fast (~1-2 min)
python -m scripts.train --model sarima                # ~30-60 min
python -m scripts.train --model lstm                  # ~15-45 min CPU
python -m scripts.train --model all
```

### Auto-selecting ARIMA/SARIMA orders

```bash
python -m scripts.train --model arima --auto-order    # pmdarima.auto_arima
python -m scripts.train --model sarima --auto-order   # statsforecast AutoARIMA
```

ARIMA uses `pmdarima` on the `statsmodels` backend. SARIMA uses `statsforecast`'s Cython AutoARIMA — `statsmodels.SARIMAX` allocates a `(state_dim², T)` Kalman covariance array during fit and OOMs on multi-year hourly data with seasonal=24, while `statsforecast` handles the same problem in tens of MB.

Search bounds: ARIMA `max_p=5, max_q=5, max_d=2`; SARIMA `max_p=2, max_q=2, max_P=1, max_Q=1, max_d=1, max_D=1, m=24`.

Override defaults: `python -m scripts.fetch_nasa --start 2018-01-01 --end 2024-12-30 --output custom.csv` then `python -m scripts.train --model all --data custom.csv`.

Artifacts land in `artifacts/{arima.pkl, sarima.pkl, lstm/{model.keras,meta.pkl}}` and `artifacts/nasa/training_data.csv`. The API loads whichever artifacts exist at startup; missing ones are skipped (warning logged).

## Evaluating

### Detection — precision / recall / F1

```bash
python -m scripts.evaluate_detection                              # default detectors, k=3.0
python -m scripts.evaluate_detection --sweep-k 1.0 10.0 0.5
python -m scripts.evaluate_detection --methods all                # include lstm_residual etc.
python -m scripts.evaluate_detection --inject spike,drift
```

Default detectors are `zscore,seasonal_zscore`. Pass `--methods all` (or include `arima_residual`, `sarima_residual`, `lstm_residual` explicitly) to evaluate forecasters wrapped as detectors via the residual rule.

### Forecasting — MAE / RMSE / MAPE on clean test data

```bash
python -m scripts.evaluate_forecast                               # all forecasters, h={1,6,24}
python -m scripts.evaluate_forecast --horizons 1 12 24
python -m scripts.evaluate_forecast --methods arima,lstm
python -m scripts.evaluate_forecast --max-points 1000             # cap walk length
```

This walks the **unperturbed** test slice in one-step-ahead mode: at each timestamp, predict the next `h` values, compare against the actual continuation, then `update()` with the truth. No anomaly injection — this is the apples-to-apples forecast quality comparison.

## Run

```bash
# 1. Fetch NASA + train forecasters (one time)
python -m scripts.fetch_nasa
python -m scripts.train --model all

# 2. (Optional) Backtests
python -m scripts.evaluate_detection
python -m scripts.evaluate_forecast

# 3. Start API — loads forecaster artifacts + fits detectors from the NASA CSV
uvicorn api.main:app --port 8082
```

## API

- `GET  /health` — liveness + lists of loaded detectors and forecasters
- `GET  /models` — per-model metadata (role: `detector` / `forecaster`)
- `POST /detect` — body `{groupId, sensorId, time, value}` → per-method anomaly verdict + combined `is_anomaly`
- `POST /forecast` — body `{groupId, sensorId, time, horizon}` → per-method `[t+1 ... t+horizon]` predictions

## Config

All via `.env` (see `.env.example`). Key values:

- `MONGO_URI`, `MONGO_DB` — same Mongo as `cassavaBE`
- `GROUP_ID` — defaults to the hardcoded weather-station group from `edge/edge_to_mongo_weather.c`
- `SENSOR_ID` — `temperature` for now (single sensor MVP)
- `ANOMALY_K` — threshold; `|score| > K` ⇒ anomaly
- `RESAMPLE_FREQ` — pandas freq string for hourly resampling

## Not yet implemented

- Imputation (filling in anomalous values)
- Writing verdicts/forecasts to a Mongo collection
- FE visualization
- Auto-restart / systemd unit for prod
