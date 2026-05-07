# Branch `feat/anomaly-ml` — ARIMA + SARIMA + LSTM

> Tài liệu thiết kế cho nhánh **ML-based anomaly detection**. Triển khai ba
> mô hình dự báo (forecasting) — ARIMA, SARIMA, LSTM — chạy song song trên
> nhiệt độ hourly. Bất thường được xác định khi sai số dự báo (residual)
> vượt ngưỡng theo độ lệch chuẩn của residual lúc training.

> **Cập nhật 2026-05-07 (nhánh `feat/anomaly-compare`):** sau refactor, ba
> mô hình ARIMA/SARIMA/LSTM được tách rõ vai trò thành **Forecaster** (giao diện
> `predict(time, h) → ForecastResult`) ở [`ml/forecasters/`](../ml/forecasters/),
> còn vai trò detector được kế thừa qua wrapper
> [`ml.detectors.ResidualDetector`](../ml/detectors/residual.py) — composition
> over inheritance. Kết quả so sánh giữa 5 detector (zscore, seasonal_zscore,
> arima/sarima/lstm-residual) và 3 forecaster (ARIMA, SARIMA, LSTM) ở
> [`docs/comparison-results.md`](comparison-results.md).

---

## 1. Tổng quan

Nhánh này triển khai **Tier ML** trong kiến trúc anomaly detection của
CASSAVA_IOT:

```
MQTT raw value (per-minute)        ───►  Range Check (Java BE, per-minute)
                                          │
Mongo sensor_value (per-minute)    ───►  hourly resample (pandas)
                                          │
                                          ▼
                              ┌───────────┴───────────┐
                              │  ARIMA (univariate)   │
                              │  SARIMA (univariate)  │ ───►  residual
                              │  LSTM   (multivariate)│       so với σ
                              └───────────┬───────────┘
                                          │
                                          ▼
                                    is_anomaly?
```

Sister branch `feat/anomaly-zscore` triển khai cách tiếp cận thuần thống kê
(Modified Z-score + Seasonal Z-score) — xem `docs/zscore-branch.md` trên
nhánh đó.

---

## 2. Cơ chế detection chung

Cả ba mô hình đều theo paradigm **forecasting-based anomaly detection**:

1. **Train**: học pattern bình thường từ data lịch sử
2. **Predict**: với mỗi điểm mới, dự báo giá trị kỳ vọng `x̂_t`
3. **Residual**: `r_t = x_t - x̂_t`
4. **Score**: `z = |r_t| / σ_residual`, trong đó `σ_residual` được calibrate
   từ residual của tập training
5. **Verdict**: `is_anomaly = z > k` (mặc định `k = 3.0`)

Điểm chung (sau refactor compare-branch): cả ba mô hình implement interface
`Forecaster` ở [`ml/base.py`](../ml/base.py) — `fit`, `predict(time, h)`,
`update(value, time)`, `save`, `load`. Để phục vụ vai trò detector, wrap qua
`ResidualDetector(forecaster)` ở [`ml/detectors/residual.py`](../ml/detectors/residual.py)
— wrapper này expose interface `AnomalyDetector` với `score(value, time, k)`,
nội bộ gọi `predict()` rồi áp ngưỡng `|residual|/σ > k`.

---

## 3. ARIMA — `ml/forecasters/arima.py`

### 3.1. Cơ sở toán học

ARIMA(p, d, q) gồm ba thành phần:

- **AR(p)** — AutoRegressive: giá trị hiện tại phụ thuộc tuyến tính vào `p`
  giá trị quá khứ.
  
  ```
  x_t = c + φ₁·x_{t-1} + φ₂·x_{t-2} + ... + φ_p·x_{t-p} + ε_t
  ```

- **I(d)** — Integrated: vi phân chuỗi `d` lần để biến chuỗi không dừng
  thành dừng. Với nhiệt độ hourly: `d = 1` thường đủ (loại trừ trend chậm).

- **MA(q)** — Moving Average: giá trị hiện tại phụ thuộc vào `q` sai số
  ngẫu nhiên trong quá khứ.
  
  ```
  x_t = μ + ε_t + θ₁·ε_{t-1} + ... + θ_q·ε_{t-q}
  ```

### 3.2. Cấu hình hiện tại

Default class-level `order = (2, 1, 2)` (vẫn còn trong `ArimaForecaster.__init__`),
nhưng sau khi áp dụng `--auto-order` ở `scripts/train.py` (đã xong trên
nhánh compare), order thực tế được dùng là **(5, 1, 4)** — kết quả từ
`pmdarima.auto_arima` stepwise search theo AIC. Order tự chọn này được lưu
trong `artifacts/arima.pkl` và `_build_for_refit()` trong evaluator tự pick
ra dùng lại khi refit trên train slice.

### 3.3. Online forecasting

`predict(time, h)` thực hiện `self._results.forecast(steps=h)` — dự báo h
bước. `update(value, time)` extend model state qua
`self._results.append(refit=False)` — không re-fit (rẻ), nhưng vẫn dùng được
giá trị mới cho lần forecast tiếp theo.

Khi vai trò là **detector** (qua `ResidualDetector`):
1. `predict(time, 1)` → `forecast`
2. `residual = actual - forecast`
3. `score = |residual| / residual_std`, `is_anomaly = score > k`
4. `update(actual, time)` — model mở rộng state cho lần sau

Đây là cơ chế **online forecasting** — model luôn nhìn thấy toàn bộ history
khi dự báo điểm tiếp theo.

### 3.4. Yêu cầu data

- Tối thiểu **30 hourly points** (≈ 1.5 ngày) — NASA POWER (3 năm) thừa xa
  yêu cầu này (~26,000 rows)
- Hourly grid đều (NASA cung cấp lưới giờ hoàn chỉnh)
- NASA flag missing là `-999` → `nasa_loader` đã chuyển sang `NaN`; detector
  tự interpolate tối đa 3 điểm liên tiếp, drop phần còn thiếu

---

## 4. SARIMA — `ml/forecasters/sarima.py`

### 4.1. Cơ sở toán học

SARIMA(p, d, q)(P, D, Q, s) mở rộng ARIMA với các thành phần seasonal:

- **(P, D, Q)** — AR/I/MA seasonal terms
- **s** — chu kỳ seasonal (số time step cho 1 chu kỳ)

Với nhiệt độ hourly và chu kỳ daily: `s = 24` (24 giờ = 1 ngày).

### 4.2. Cấu hình hiện tại

Default class-level `order = (1, 1, 1)`, `seasonal_order = (1, 1, 1, 24)`,
nhưng auto-search bằng `statsforecast.AutoARIMA` (`--auto-order` trong
`scripts/train.py`) chọn ra **(2, 0, 1)(1, 1, 0, 24)** — đây là order thực
tế đang được dùng trong `artifacts/sarima.pkl` và evaluator.

Diễn giải order tự chọn:
- `(p=2, d=0, q=1)`: 2 lag autoregressive ngắn hạn, không cần vi phân
- `(P=1, D=1, Q=0)`: 1 seasonal AR + vi phân seasonal (D=1) — đủ bắt cycle
  daily mà không cần seasonal MA
- `s=24`: chu kỳ daily — bắt pattern "nhiệt độ trưa cao hơn nhiệt độ đêm"

Backend là `statsforecast` (Cython ARIMA), thay thế `statsmodels.SARIMAX`
do statsmodels OOM trên 6 năm hourly với seasonal=24 (allocation `(state_dim², T)`
~1 GiB). `statsforecast` xử lý cùng problem trong vài chục MB.

### 4.3. Yêu cầu data

- Tối thiểu **3 chu kỳ** = `3 × 24 = 72 hourly points` (3 ngày)
- Trên NASA POWER (3 năm, ~26,000 rows) seasonal terms ổn định ngay từ
  default config — không cần lo thiếu data như khi train trên Mongo MQTT

### 4.4. So với ARIMA

SARIMA thêm capacity bắt seasonality, nhưng:
- **Train chậm hơn** (nhiều parameters, optimizer chạy lâu)
- **Cần data dài hơn** để identification đúng
- **Forecast chính xác hơn** nếu chuỗi thật sự có chu kỳ (nhiệt độ daily — có)

Trên chuỗi không có seasonality (ví dụ điện áp nguồn ổn định), SARIMA
overfit và performance kém hơn ARIMA.

---

## 5. LSTM — `ml/forecasters/lstm.py`

### 5.1. Tại sao LSTM khác ARIMA/SARIMA

| Tiêu chí | ARIMA/SARIMA | LSTM |
|---|---|---|
| Dữ liệu input | Univariate (chỉ temperature) | Multivariate (5 biến thời tiết) |
| Quan hệ | Tuyến tính | Phi tuyến |
| Source training | NASA POWER (cột `temperature`) | NASA POWER (đa biến) |
| Số tham số | ~5-10 | ~20,000 |
| Cần data | 30-72 điểm | 4000+ điểm |

LSTM ở đây không phải chỉ là "ARIMA mạnh hơn" — nó học pattern **đa biến**
(nhiệt độ phụ thuộc vào độ ẩm, gió, bức xạ, mưa) và pattern **phi tuyến**
mà ARIMA không bắt được.

### 5.2. Architecture

```
Input shape: (batch, 48, 5)        ← 48 hourly steps, 5 features
       │
LSTM(64, return_sequences=False)   ← state vector 64-dim
       │
Dropout(0.2)                       ← regularization
       │
Dense(32, relu)                    ← projection
       │
Dense(1)                           ← predicted temperature (scaled)
```

Input features (theo thứ tự cố định):
1. `temperature` (target — mô hình dự báo chính nó)
2. `relativeHumidity`
3. `wind`
4. `rain`
5. `radiation`

Output: 1 giá trị scalar = nhiệt độ scaled (0-1) cho giờ tiếp theo. Inverse
transform qua `MinMaxScaler` để ra °C.

### 5.3. Tại sao train trên NASA POWER thay vì MQTT?

Lý do áp dụng cho **cả ba model** (ARIMA, SARIMA, LSTM) trên nhánh này:

1. **Data length**: NASA cho phép pull nhiều năm (3 năm = ~26,000 hourly
   rows). MQTT mới deploy có thể chỉ vài tuần — không đủ train LSTM, và
   cũng mỏng cho SARIMA(s=24) học seasonal terms ổn định.
2. **Multivariate có sẵn**: NASA cung cấp đồng thời 5 biến thời tiết tại
   cùng tọa độ; MQTT có thể có gap ở 1-2 sensor. Quan trọng cho LSTM, có
   ích cho ARIMA/SARIMA khi mở rộng sang exogenous variables sau này.
3. **Ground truth chất lượng**: NASA reanalysis là model output đã được
   validate; ít nhiễu hơn raw sensor.
4. **Generalization**: model học pattern thời tiết của cả khu vực, không
   overfit vào quirks của 1 cảm biến cụ thể.
5. **Reproducibility**: NASA là nguồn cố định ai cũng pull được; báo cáo
   thesis tái lập được không cần truy cập Mongo cluster.

Detection thì vẫn so sánh **prediction model** vs **MQTT actual** — nếu
MQTT lệch khỏi "expected weather pattern" của khu vực → anomaly. Lưu ý
domain shift NASA → MQTT (xem §7).

### 5.4. NASA POWER API

[`ml/nasa_loader.py`](../ml/nasa_loader.py) wrap endpoint:

```
https://power.larc.nasa.gov/api/temporal/hourly/point
  ?community=AG
  &latitude=21.0075         ← Hà Nội, khớp Field.java#hourlyET()
  &longitude=105.5416
  &start=YYYYMMDD
  &end=YYYYMMDD
  &parameters=T2M,RH2M,WS2M,PRECTOTCORR,ALLSKY_SFC_SW_DWN
  &time-standard=UTC
```

**Variable mapping**:

| NASA name | Schema name | Đơn vị |
|---|---|---|
| `T2M` | `temperature` | °C |
| `RH2M` | `relativeHumidity` | % |
| `WS2M` | `wind` | m/s |
| `PRECTOTCORR` | `rain` | mm/h |
| `ALLSKY_SFC_SW_DWN` | `radiation` | MJ/m²/h |

Đơn vị radiation **MJ/m²/h** khớp đúng convention canonical của hệ thống
(theo CLAUDE.md, không phải W/m²).

**Caching**: response được cache thành CSV dưới `artifacts/nasa/`. File
name: `{start}_{end}_{lat}_{lon}.csv`. Lần fetch sau dùng cache, không
gọi lại API.

**Delay**: NASA POWER hourly có delay ~60 ngày cho data finalized.
`fetch_years()` đã trừ 60 ngày để tránh điểm NRT (near real-time).

### 5.5. Forecasting mechanism

Khi `predict(time, h=1)` được gọi:
1. Query Mongo (hoặc `_offline_context` khi backtest) lấy 48h gần nhất của
   5 sensor → multivariate context
2. Scale qua `MinMaxScaler` đã fit lúc training
3. Reshape thành tensor `(1, 48, 5)` → forward pass LSTM → giá trị scaled
4. Inverse transform → predicted temperature (°C)

Khi `predict(time, h>1)`: roll window iteratively — predict t+1, append
`[ŷ_t+1, last-features-unchanged]`, predict t+2, lặp lại. Hạn chế kiến
trúc này (xem [comparison-results.md §3](comparison-results.md)): các feature
khác (humidity, wind, radiation) **không** giữ nguyên trong thực tế, nên
input window drift out-of-distribution rất nhanh ở `h ≥ 6`. Backtest cho
thấy LSTM **vô địch ở h=1 (MAE 1.75°C)** nhưng **sụp đổ ở h≥6** (MAE 9–10°C).

Khi vai trò là detector (qua `ResidualDetector`): tương tự ARIMA — `predict(time, 1)`,
threshold residual qua `residual_std`. Latency ~50-200ms/call (Mongo query +
forward pass). OK cho hourly cadence (24 calls/day).

### 5.6. Yêu cầu data

- Training: ≥ `48 + 50 = 98` rows multivariate (NASA dễ vượt)
- Detection: ≥ 48 hourly rows multivariate trong Mongo cho window context
  (tức ~2 ngày MQTT phải có đủ 5 sensor, không gap quá 3 điểm liên tiếp)

---

## 6. Cấu hình & vận hành

### 6.1. Setup

```bash
cd ml-service
python -m venv .venv && source .venv/Scripts/activate
pip install -r requirements.txt   # ~500MB (TensorFlow!)
cp .env.example .env
```

### 6.2. Kiểm tra data trước khi train

```bash
python -m scripts.check_data
```

In ra số điểm hourly hiện có trong Mongo, % gap, và đánh giá khả năng
training cho từng model.

### 6.3. Train

Workflow chia 2 bước — fetch NASA POWER 1 lần ra CSV, rồi train từ file đó.
Tách network fetch (chậm) khỏi vòng lặp iterate model (nhanh).

```bash
# 1. MỘT LẦN: fetch NASA POWER -> single CSV tại
#    artifacts/nasa/training_data.csv (default: 2020-01-01 .. 2025-12-30,
#    bỏ qua giai đoạn corrupt từ 2025-12-31 trở đi)
python -m scripts.fetch_nasa

# 2. Train từ CSV vừa fetch
python -m scripts.train --model arima
python -m scripts.train --model sarima       # chậm (MLE trên ~52k rows)
python -m scripts.train --model lstm         # rất chậm lần đầu (LSTM training)
python -m scripts.train --model all          # cả 3 model

# Override range / output:
python -m scripts.fetch_nasa --start 2018-01-01 --end 2024-12-30 --output custom.csv
python -m scripts.train --model all --data custom.csv
```

Artifact lưu vào:
- `artifacts/arima.pkl`
- `artifacts/sarima.pkl`
- `artifacts/lstm/model.keras` + `artifacts/lstm/meta.pkl`
- `artifacts/nasa/*.csv` (cache NASA)

### 6.4. Run API

```bash
uvicorn api.main:app --port 8082
```

Lúc startup, app load các artifact tồn tại. Nếu chưa train → log warning,
endpoint `/detect` vẫn trả về 200 nhưng `methods` rỗng.

### 6.5. Test API

```bash
curl -X POST http://localhost:8082/detect \
  -H "Content-Type: application/json" \
  -d '{
    "groupId": "69e35b13e405c05c3dab13c9",
    "sensorId": "temperature",
    "time": "2026-05-02T10:00:00Z",
    "value": 28.5
  }'
```

Response chứa verdict từ từng model + verdict tổng (`is_anomaly = OR`).

### 6.6. Backtest comparison (sau refactor)

Hai script tách riêng cho hai vai trò:

```bash
# Detection — precision/recall/F1, sweep ngưỡng k
python -m scripts.evaluate_detection --methods all \
    --sweep-k 1.0 10.0 0.5 --sweep-out artifacts/k_sweep.csv

# Forecast — MAE/RMSE/MAPE qua nhiều horizon, walk online trên test sạch
python -m scripts.evaluate_forecast --methods arima,sarima,lstm \
    --horizons 1 6 24 --max-points 2000 --out artifacts/forecast.csv
```

Kết quả mới nhất (auto-selected order, refit-on-train, 80/20 split): xem
[`docs/comparison-results.md`](comparison-results.md).

---

## 7. Hạn chế & cải thiện trong tương lai

### Đã biết

1. **Domain shift NASA → MQTT** — NASA reanalysis ít nhiễu hơn raw sensor;
   `residual_std` calibrate trên NASA sẽ nhỏ hơn noise floor thật của MQTT.
   Hệ quả: lúc detect trên MQTT, residual có xu hướng lớn hơn về mặt tỉ lệ
   `σ` → tăng false positive với cùng `k=3.0`. Mitigation:
   - Tune `k` cao hơn (ví dụ 4.0-5.0) sau khi backtest
   - Hoặc re-calibrate `residual_std` bằng pass qua data Mongo sau khi
     fit trên NASA (giữ tham số model NASA, dùng noise floor MQTT)
2. **ARIMA/SARIMA order hardcoded** — ĐÃ FIX trên nhánh compare:
   `pmdarima.auto_arima()` cho ARIMA và `statsforecast.AutoARIMA` cho
   SARIMA. Order tự chọn (5,1,4) và (2,0,1)(1,1,0,24) đã được lưu vào
   artifact và dùng tự động bởi evaluator.
3. **LSTM hyperparameters chưa tune** — `epochs=30, batch=32, LSTM(64)`
   là default. Grid search hoặc Bayesian opt sẽ tốt hơn.
4. **Không có online retraining** — sau khi train, model freeze. Nếu khí
   hậu thay đổi (ví dụ chuyển mùa), cần manual `python -m scripts.train`
   lại.
5. **Detection latency LSTM** — 50-200ms/call vì query Mongo. Có thể
   cache last 48h trong memory để < 10ms.
6. **Không log verdict** — hiện chỉ trả về API response. Để compute
   metrics dài hạn cần collection `sensor_anomaly` (kế hoạch trong PR
   imputation sau).

### Out of scope branch này

- **Imputation** (vá data khi anomaly) — nhánh sau
- **Multi-sensor detection** — hiện chỉ temperature
- **Per-field model** — hiện 1 model global cho default group
- **FE visualization** — chưa có

---

## 8. So sánh với `feat/anomaly-zscore`

| Tiêu chí | ML branch | Z-score branch |
|---|---|---|
| Số dòng code | ~600 | ~150 |
| Dependencies | TF + statsmodels (~500MB) | numpy thuần |
| Train time | Vài giờ (LSTM) | Tức thì |
| Cần data ngoài | NASA POWER (cả 3 model) | Không |
| Hiểu phi tuyến | Có (LSTM) | Không |
| Hiểu seasonality | Có (SARIMA, LSTM) | Có (Seasonal Z) |
| Giải thích được | Khó (LSTM black box) | Dễ |
| Phù hợp realtime | OK (50-200ms/call) | Tức thì |

Để so sánh trực tiếp toàn bộ 5 detector (zscore, seasonal_zscore, +
arima/sarima/lstm-residual) và 3 forecaster, dùng nhánh compare:

```bash
git checkout feat/anomaly-compare
python -m scripts.evaluate_detection --methods all --sweep-k 1.0 10.0 0.5
python -m scripts.evaluate_forecast --methods arima,sarima,lstm --horizons 1 6 24 --max-points 2000
```

Bản kết quả đã có sẵn ở [`docs/comparison-results.md`](comparison-results.md).
