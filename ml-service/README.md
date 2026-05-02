# ml-service

Python anomaly detection service for CASSAVA_IOT. Reads sensor history from MongoDB, detects anomalies in hourly-resampled weather data. Runs as a separate process from `cassavaBE`; communicates via HTTP (FastAPI on port 8082 by default).

**This branch (`feat/anomaly-zscore`) implements:**

- **Modified Z-score** (`ml/zscore.py`) — sliding window of 60 hourly points; uses MAD instead of std → robust to outliers in the window. Iglewicz & Hoaglin formula.
- **Seasonal Z-score** (`ml/seasonal_zscore.py`) — hour-of-day buckets; compares value against historical distribution at the same hour.

Sister branch `feat/anomaly-ml` implements ARIMA + SARIMA + LSTM. Use:

```bash
git checkout feat/anomaly-zscore && python -m scripts.evaluate --methods all > /tmp/zscore.txt
git checkout feat/anomaly-ml     && python -m scripts.evaluate --methods all > /tmp/ml.txt
diff /tmp/zscore.txt /tmp/ml.txt
```

Detection cadence on both branches is **hourly**. The Java BE's `RangeCheckService` continues to run per-minute as Tier 1 — this service is independent of it.

## Setup

Requires Python 3.13.

```bash
cd ml-service
python -m venv .venv
source .venv/Scripts/activate    # Windows Git Bash / mingw
# .venv\Scripts\activate         # Windows cmd / PowerShell
# source .venv/bin/activate      # Linux / macOS

pip install -r requirements.txt
cp .env.example .env             # adjust if needed
```

## Run

```bash
# 1. Sanity-check data availability before doing anything else
python -m scripts.check_data

# 2. (Branch-specific) train / fit detectors — see branch README
python -m scripts.evaluate --methods all

# 3. Start API
uvicorn api.main:app --port 8082
```

## API

- `GET  /health` — liveness + list of loaded detectors
- `GET  /models` — detector metadata
- `POST /detect` — body `{groupId, sensorId, time, value}` → per-method verdict + combined `is_anomaly`

## Layout

```
ml/                # detector implementations (per branch)
api/               # FastAPI + Pydantic schemas
scripts/           # CLI: check_data, evaluate, train (ML branch only)
artifacts/         # model files (gitignored)
```

## Config

All via `.env` (see `.env.example`). Key values:

- `MONGO_URI`, `MONGO_DB` — same Mongo as `cassavaBE`
- `GROUP_ID` — defaults to the hardcoded weather-station group from `edge/edge_to_mongo_weather.c`
- `SENSOR_ID` — `temperature` for now (single sensor MVP)
- `ANOMALY_K` — threshold; `|score| > K` ⇒ anomaly
- `RESAMPLE_FREQ` — pandas freq string for hourly resampling

## Not yet implemented

- Imputation (filling in anomalous values)
- Writing verdicts to a Mongo collection
- FE visualization
- Auto-restart / systemd unit for prod
