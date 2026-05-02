# Branch `feat/anomaly-ml` — ARIMA + SARIMA + LSTM

> Tài liệu thiết kế cho nhánh **ML-based anomaly detection**. Triển khai ba
> mô hình dự báo (forecasting) — ARIMA, SARIMA, LSTM — chạy song song trên
> nhiệt độ hourly. Bất thường được xác định khi sai số dự báo (residual)
> vượt ngưỡng theo độ lệch chuẩn của residual lúc training.

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

Điểm chung: cả ba mô hình implement cùng interface `AnomalyDetector` ở
[`ml/base.py`](../ml/base.py) — `fit`, `score`, `save`, `load`. API và
evaluation harness gọi chúng đa hình.

---

## 3. ARIMA — `ml/arima_model.py`

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

Default `order = (2, 1, 2)` — chọn theo kinh nghiệm cho chuỗi nhiệt độ:
- `p = 2`: 2 lag autoregressive đủ bắt local autocorrelation
- `d = 1`: vi phân 1 lần loại trend
- `q = 2`: 2 lag MA đủ smooth nhiễu ngắn hạn

**Hạn chế**: order hardcode, không tối ưu theo data thực. Cải thiện trong
tương lai: dùng `pmdarima.auto_arima()` hoặc `statsforecast.AutoARIMA` để
tự stepwise search qua AIC/BIC.

### 3.3. Online detection

`score(actual, time)` thực hiện:
1. `forecast = self._results.forecast(steps=1)` — dự báo 1 bước
2. So với `actual` → `residual`
3. Sau khi score xong, **append** `actual` vào model state qua
   `self._results.append(refit=False)` — không re-fit (rẻ), nhưng vẫn dùng
   được giá trị mới cho lần forecast tiếp theo

Đây là cơ chế **online forecasting** — model luôn nhìn thấy toàn bộ history
khi dự báo điểm tiếp theo.

### 3.4. Yêu cầu data

- Tối thiểu **30 hourly points** (≈ 1.5 ngày)
- Hourly grid đều (data.py đã `.resample('1h').mean()`)
- Gap được fill bằng linear interpolation tối đa 3 điểm liên tiếp; điểm còn
  thiếu bị drop

---

## 4. SARIMA — `ml/sarima_model.py`

### 4.1. Cơ sở toán học

SARIMA(p, d, q)(P, D, Q, s) mở rộng ARIMA với các thành phần seasonal:

- **(P, D, Q)** — AR/I/MA seasonal terms
- **s** — chu kỳ seasonal (số time step cho 1 chu kỳ)

Với nhiệt độ hourly và chu kỳ daily: `s = 24` (24 giờ = 1 ngày).

### 4.2. Cấu hình hiện tại

```python
order = (1, 1, 1)
seasonal_order = (1, 1, 1, 24)
```

Diễn giải:
- `(p=1, d=1, q=1)`: ARIMA component đơn giản trên scale giờ
- `(P=1, D=1, Q=1)`: SARIMA component đơn giản trên scale ngày
- `s=24`: chu kỳ daily — bắt pattern "nhiệt độ trưa cao hơn nhiệt độ đêm"

`SARIMAX` được dùng (thay vì `SARIMA` cũ) với:
- `enforce_stationarity=False`
- `enforce_invertibility=False`

→ tránh constraint quá chặt làm fit fail trên small sample size.

### 4.3. Yêu cầu data

- Tối thiểu **3 chu kỳ** = `3 × 24 = 72 hourly points` (3 ngày)
- Tốt nhất ≥ 14 ngày (336 points) để seasonal terms ổn định

### 4.4. So với ARIMA

SARIMA thêm capacity bắt seasonality, nhưng:
- **Train chậm hơn** (nhiều parameters, optimizer chạy lâu)
- **Cần data dài hơn** để identification đúng
- **Forecast chính xác hơn** nếu chuỗi thật sự có chu kỳ (nhiệt độ daily — có)

Trên chuỗi không có seasonality (ví dụ điện áp nguồn ổn định), SARIMA
overfit và performance kém hơn ARIMA.

---

## 5. LSTM — `ml/lstm_model.py`

### 5.1. Tại sao LSTM khác ARIMA/SARIMA

| Tiêu chí | ARIMA/SARIMA | LSTM |
|---|---|---|
| Dữ liệu input | Univariate (chỉ temperature) | Multivariate (5 biến thời tiết) |
| Quan hệ | Tuyến tính | Phi tuyến |
| Source training | Mongo MQTT data | NASA POWER (3 năm) |
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

Lý do chính:
1. **Data length**: NASA cho phép pull nhiều năm (3 năm = ~26,000 hourly
   rows). MQTT mới deploy có thể chỉ vài tuần — không đủ train LSTM.
2. **Multivariate có sẵn**: NASA cung cấp đồng thời 5 biến thời tiết tại
   cùng tọa độ; MQTT có thể có gap ở 1-2 sensor.
3. **Ground truth chất lượng**: NASA reanalysis là model output đã được
   validate; ít nhiễu hơn raw sensor.
4. **Generalization**: model học pattern thời tiết của cả khu vực, không
   overfit vào quirks của 1 cảm biến cụ thể.

Detection thì vẫn so sánh **prediction LSTM** vs **MQTT actual** — nếu
MQTT lệch khỏi "expected weather pattern" của khu vực → anomaly.

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

### 5.5. Detection mechanism

Khi `score(actual, time)` được gọi:
1. Query Mongo lấy 48h gần nhất của 5 sensor (multivariate context)
2. Scale qua `MinMaxScaler` đã fit lúc training
3. Reshape thành tensor `(1, 48, 5)`
4. Predict qua LSTM → giá trị scaled
5. Inverse transform → predicted temperature (°C)
6. So sánh với `actual` → residual → z-score → verdict

Mỗi `score()` call có **1 round-trip Mongo** + **1 forward pass LSTM**.
Latency ~50-200ms tùy cluster Mongo. OK cho hourly cadence (24 calls/day).

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

```bash
# ARIMA + SARIMA: dùng Mongo MQTT data
python -m scripts.train --model arima --lookback-days 30
python -m scripts.train --model sarima --lookback-days 30

# LSTM: fetch NASA + train (rất chậm lần đầu)
python -m scripts.train --model lstm --nasa-years 3

# Cả 3 cùng lúc
python -m scripts.train --model all
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

### 6.6. Backtest comparison

```bash
python -m scripts.evaluate --methods all --inject spike,outlier,drift
```

In bảng precision/recall/F1 + MAE/RMSE cho cả 3 model trên synthetic
anomalies.

---

## 7. Hạn chế & cải thiện trong tương lai

### Đã biết

1. **ARIMA/SARIMA order hardcoded** — nên thêm `pmdarima.auto_arima()`
   để stepwise search theo AIC. Đã có TODO.
2. **LSTM hyperparameters chưa tune** — `epochs=30, batch=32, LSTM(64)`
   là default. Grid search hoặc Bayesian opt sẽ tốt hơn.
3. **Không có online retraining** — sau khi train, model freeze. Nếu khí
   hậu thay đổi (ví dụ chuyển mùa), cần manual `python -m scripts.train`
   lại.
4. **Detection latency LSTM** — 50-200ms/call vì query Mongo. Có thể
   cache last 48h trong memory để < 10ms.
5. **Không log verdict** — hiện chỉ trả về API response. Để compute
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
| Cần data ngoài | NASA POWER (LSTM) | Không |
| Hiểu phi tuyến | Có (LSTM) | Không |
| Hiểu seasonality | Có (SARIMA, LSTM) | Có (Seasonal Z) |
| Giải thích được | Khó (LSTM black box) | Dễ |
| Phù hợp realtime | OK (50-200ms/call) | Tức thì |

Để so sánh trực tiếp:

```bash
git checkout feat/anomaly-zscore && python -m scripts.evaluate --methods all > /tmp/zscore.txt
git checkout feat/anomaly-ml     && python -m scripts.evaluate --methods all > /tmp/ml.txt
diff /tmp/zscore.txt /tmp/ml.txt
```
