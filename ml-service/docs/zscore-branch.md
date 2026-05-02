# Branch `feat/anomaly-zscore` — Modified Z-score + Seasonal Z-score

> Tài liệu thiết kế cho nhánh **statistical anomaly detection**. Triển khai
> hai phương pháp thuần thống kê — Modified Z-score (sliding window) và
> Seasonal Z-score (hour-of-day buckets). Không train ML, không phụ thuộc
> nguồn dữ liệu ngoài, code gọn (~150 dòng cho 2 detector).

---

## 1. Tổng quan

Nhánh này triển khai **Tier statistical** trong kiến trúc anomaly detection
của CASSAVA_IOT:

```
MQTT raw value (per-minute)        ───►  Range Check (Java BE, per-minute)
                                          │
Mongo sensor_value (per-minute)    ───►  hourly resample (pandas)
                                          │
                                          ▼
                              ┌───────────┴────────────┐
                              │  Modified Z-score      │
                              │  (sliding window 60h)  │ ───►  z_mod > 3?
                              │                        │
                              │  Seasonal Z-score      │
                              │  (hour-of-day buckets) │ ───►  z_seasonal > 3?
                              └───────────┬────────────┘
                                          │
                                          ▼
                                    is_anomaly = OR
```

Sister branch `feat/anomaly-ml` triển khai cách tiếp cận ML
(ARIMA + SARIMA + LSTM) — xem `docs/ml-branch.md` trên nhánh đó.

---

## 2. Tại sao thống kê (không phải ML)?

| Tiêu chí | Statistical | ML |
|---|---|---|
| **Lượng code** | ~150 dòng | ~600 dòng |
| **Dependency** | numpy thuần | TF + statsmodels (~500MB) |
| **Cần training** | Không | Có (vài giờ cho LSTM) |
| **Cần data ngoài** | Không | Có (NASA POWER cho LSTM) |
| **Giải thích** | Dễ (toán cấp 3) | Khó (LSTM black box) |
| **Latency** | < 1ms | 50-200ms (LSTM) |
| **Bắt được pattern phi tuyến** | Không | Có (LSTM) |
| **Bắt được anomaly ngữ cảnh** | Có (Seasonal) | Có |
| **Bắt được spike đột ngột** | Có (Modified Z) | Có |

Statistical phù hợp khi:
- Data ngắn (vài tuần đầu deploy, ML chưa đủ data)
- Cần real-time response (< 1ms)
- Ưu tiên giải thích được (audit, debug, defense)
- Không có resource huấn luyện (không có GPU, không có dữ liệu ngoài)

---

## 3. Modified Z-score — `ml/zscore.py`

### 3.1. Cơ sở toán học

Z-score chuẩn:

```
z = (x - μ) / σ
```

Vấn đề: μ (mean) và σ (std) đều bị ảnh hưởng bởi outlier. Nếu cửa sổ trượt
chứa chính điểm bất thường, μ bị kéo lệch, σ bị thổi phồng — điểm bất
thường có thể "ẩn mình" trong phân phối méo. Đây là **masking effect**.

**Modified Z-score** (Iglewicz & Hoaglin, 1993) thay thế bằng:

```
z_mod = 0.6745 × (x - median) / MAD
```

trong đó:
- `median` = trung vị của cửa sổ
- `MAD` = `median(|x_i - median|)` — Median Absolute Deviation

Median và MAD là robust statistics — không bị ảnh hưởng bởi outlier (chừng
nào outlier < 50% data). Hệ số `0.6745` là consistency constant giúp `z_mod`
ước lượng `(x - μ)/σ` đúng dưới giả định phân phối chuẩn.

### 3.2. Cấu hình

```python
window = 60         # 60 hourly points = 2.5 ngày context
threshold k = 3.0   # |z_mod| > 3 → anomaly
```

Nguyên gốc Iglewicz & Hoaglin recommend `k = 3.5`. Ta chọn `k = 3.0` để
khớp với threshold của Seasonal Z-score, dễ so sánh.

### 3.3. State machine

`ZScoreDetector` giữ một `deque` rolling buffer kích thước 60:

```python
self._buffer: deque[float] = deque(maxlen=60)
```

`fit(series)` seed buffer với 60 điểm cuối của training series.

`score(actual, time)` thực hiện:
1. Tính `median` và `MAD` của buffer **hiện tại** (chưa bao gồm `actual`)
2. Tính `z_mod`
3. Trả về verdict
4. Append `actual` vào buffer (đẩy điểm cũ nhất ra)

→ điểm `actual` luôn được score so với context **quá khứ** của nó, không
bao giờ self-reference.

### 3.4. Edge cases

- **Buffer < 10 points**: chưa đủ context → trả `is_anomaly=False, score=0`
  (abstain)
- **MAD ≈ 0** (chuỗi hằng số trong cửa sổ): không thể tính z → abstain

### 3.5. Hạn chế

- **Không hiểu seasonality**: 28°C lúc 14:00 và 28°C lúc 03:00 đều bình
  thường nếu cửa sổ 60h chứa cả ngày lẫn đêm
- **Cần cửa sổ đủ lớn** để median/MAD ổn định (≥ 10 điểm tối thiểu, 60
  là default an toàn)
- **Drift chậm có thể bị "thích nghi"**: nếu nhiệt độ tăng dần qua 60h,
  median trượt theo, không flag drift

→ chính vì thế Seasonal Z-score được thêm vào (bù đắp limitation này).

---

## 4. Seasonal Z-score — `ml/seasonal_zscore.py`

### 4.1. Cơ sở toán học

Ý tưởng: thay vì so với cửa sổ thời gian gần (như Modified Z), so với
**lịch sử của cùng một thời điểm trong chu kỳ** (cùng giờ trong ngày).

Công thức:

```
z_seasonal = (x_t - μ_h(t)) / σ_h(t)
```

trong đó:
- `h(t)` = bucket của thời điểm t — ở đây là `t.hour` (chu kỳ daily)
- `μ_h, σ_h` = mean và std của tất cả giá trị lịch sử thuộc bucket h

### 4.2. Ví dụ minh họa

Giả sử cần đánh giá nhiệt độ 28°C lúc 03:00 thứ Ba 16/04/2026.

- Bucket h = 3 (giờ 03:00 UTC)
- Lịch sử bucket h=3 gồm tất cả giá trị nhiệt độ lúc 03:00 từ 14 ngày
  trước: ví dụ `[22.1, 21.8, 22.5, 23.0, 21.9, ...]`
- `μ_3 ≈ 22.3`, `σ_3 ≈ 0.6`
- `z = (28 - 22.3) / 0.6 ≈ 9.5` → **ANOMALY** (rất bất thường để 28°C
  lúc 3 giờ sáng)

So sánh: Modified Z-score với cửa sổ 60h gần đó có thể cho `z ≈ 1.5` (bình
thường), vì 60h gần đó đã có nhiều giá trị 28°C lúc trưa.

### 4.3. Cấu hình

```python
buckets = 24                # 24 hour-of-day bucket
min_per_bucket = 10         # cần ≥ 10 sample/bucket để mean/std ổn định
threshold k = 3.0
```

Yêu cầu data: ≥ `10 × 24 = 240 hourly points` (≈ 10 ngày). Khuyến nghị
≥ 14 ngày để buckets ổn định hơn.

### 4.4. Implementation chi tiết

`fit(series)`:
1. Group series theo `time.hour` (UTC)
2. Với mỗi bucket có ≥ 10 sample, lưu `(mean, std)` vào dict
3. Bucket nào không đủ sample bị skip → tại detection, hour đó sẽ abstain

`score(actual, time)`:
1. Lookup `bucket = time.hour`
2. Nếu bucket không có trong dict → abstain
3. Nếu `σ ≈ 0` → abstain
4. Tính `z = (actual - μ) / σ`, return verdict

### 4.5. Lưu ý: UTC vs local hour

Mongo lưu `time` ở UTC. Pandas preserve UTC qua `pd.to_datetime(..., utc=True)`.
`time.hour` của ta là **UTC hour**, không phải local Việt Nam.

Hà Nội = UTC+7 → 14:00 local = 07:00 UTC. Pattern daily vẫn tồn tại, chỉ
shift. Không gây vấn đề chừng nào consistency: train và detect đều dùng UTC.

### 4.6. Hạn chế

- **Bucket cứng**: chỉ daily (h=0..23). Không bắt được pattern weekly
  (cuối tuần khác trong tuần) — không quan trọng cho weather, quan trọng
  cho electricity load
- **Không adapt với thay đổi mùa**: nếu chuyển từ mùa khô sang mùa mưa,
  pattern thay đổi → bucket cũ kém chính xác → cần re-fit định kỳ
- **Cần ≥ 10 ngày data** để fit được; deploy mới chưa đủ

---

## 5. Tại sao kết hợp cả hai?

| Pattern | Modified Z-score | Seasonal Z-score |
|---|---|---|
| **Spike đột ngột** | Bắt tốt | Bắt được nếu spike vượt bucket variance |
| **Drift chậm (qua nhiều giờ)** | Có thể bỏ sót (thích nghi) | Bắt tốt (so với baseline lịch sử) |
| **Anomaly ngữ cảnh** (28°C lúc 3 sáng) | Bỏ sót | Bắt tốt |
| **Constant outlier** (giá trị sentinel) | Bắt tốt | Bắt tốt |
| **Thiếu data warm-up** | Hoạt động sau 10 điểm | Cần ≥ 10 ngày |

Hai detector bổ sung nhau:
- Modified Z mạnh ở **local context, spike, real-time**
- Seasonal Z mạnh ở **historical context, drift, contextual anomaly**

`/detect` API gộp verdict bằng OR: `is_anomaly = z_mod_flag OR seasonal_flag`.
Trade-off: precision giảm chút (vì 2 source có thể độc lập false-positive),
recall cao hơn (bắt được cả 2 loại anomaly).

---

## 6. Cấu hình & vận hành

### 6.1. Setup

```bash
cd ml-service
python -m venv .venv && source .venv/Scripts/activate
pip install -r requirements.txt   # nhỏ — numpy/pandas/fastapi, không có TF
cp .env.example .env
```

### 6.2. Kiểm tra data

```bash
python -m scripts.check_data
```

Output cho biết:
- Số hourly points hiện có
- Span (cần ≥ 14 ngày cho Seasonal Z)
- Bucket sufficiency

### 6.3. Run API

```bash
uvicorn api.main:app --port 8082
```

Lúc startup, app load 30 ngày data Mongo, fit cả 2 detector. Nếu < 240
points → Seasonal Z fit fail, app vẫn start với chỉ Modified Z available.

### 6.4. Test API

```bash
curl -X POST http://localhost:8082/detect \
  -H "Content-Type: application/json" \
  -d '{
    "groupId": "69e35b13e405c05c3dab13c9",
    "sensorId": "temperature",
    "time": "2026-05-02T03:00:00Z",
    "value": 38.0
  }'
```

Response example (38°C lúc 03:00 UTC = 10:00 sáng VN, vẫn cao bất thường):

```json
{
  "time": "2026-05-02T03:00:00Z",
  "actual": 38.0,
  "is_anomaly": true,
  "methods": [
    {"name": "zscore", "predicted": 25.4, "residual": 12.6, "score": 4.2, "is_anomaly": true},
    {"name": "seasonal_zscore", "predicted": 26.1, "residual": 11.9, "score": 8.3, "is_anomaly": true}
  ]
}
```

### 6.5. Backtest

```bash
python -m scripts.evaluate --methods all --inject spike,outlier,drift
```

In bảng precision/recall/F1 cho từng method trên synthetic anomaly.

### 6.6. Train (offline saving — optional)

Hai detector này không cần "train" theo nghĩa ML, nhưng vẫn có `save()` /
`load()` nếu muốn cache state để tránh re-fit lúc startup. Không có
`scripts/train.py` riêng; detector tự fit lúc API startup.

---

## 7. Hạn chế chung & cải thiện trong tương lai

### Đã biết

1. **Threshold k=3.0 cố định** — có thể tune theo data thực để cân bằng
   precision/recall
2. **Không weight 2 detector** — verdict OR đơn thuần. Có thể dùng AND
   (precision cao hơn) hoặc weighted score (linh hoạt nhất)
3. **Re-fit không tự động** — Seasonal Z-score nên re-fit hàng tuần để
   adapt với thay đổi mùa. Hiện chỉ fit lúc startup
4. **UTC bucket có thể không tối ưu** cho người dùng Việt Nam — bucket theo
   local hour có thể trực quan hơn nhưng không khác về mặt detection

### Out of scope branch này

- **Imputation** (vá data khi anomaly) — nhánh sau
- **Multi-sensor detection** — hiện chỉ temperature
- **Per-field model** — hiện 1 model global cho default group
- **FE visualization** — chưa có
- **Verdict logging** — chưa lưu vào collection nào, chỉ trả qua API

---

## 8. So sánh với `feat/anomaly-ml`

Xem bảng so sánh chi tiết ở section 2 phía trên.

Để chạy comparison head-to-head:

```bash
git checkout feat/anomaly-zscore && python -m scripts.evaluate --methods all > /tmp/zscore.txt
git checkout feat/anomaly-ml     && python -m scripts.evaluate --methods all > /tmp/ml.txt
diff /tmp/zscore.txt /tmp/ml.txt
```

**Kỳ vọng kết quả** (chưa run thật):
- ZScore detect tốt cho `spike` + `outlier`, kém cho `drift`
- Seasonal ZScore detect tốt cho `drift` + contextual anomaly
- ML methods cạnh tranh trên cả ba pattern, đặc biệt LSTM nếu có đủ NASA
  data

Trong báo cáo thesis, có thể trình bày như "tradeoff giữa simplicity vs
capability" — cả hai approach đều hợp lý cho IoT, lựa chọn tùy:
- Resource constraint (Z-score nhẹ hơn nhiều)
- Data availability (Z-score chỉ cần 10 ngày local, ML cần NASA + nhiều
  tháng MQTT)
- Cần giải thích / audit (Z-score win)
- Cần bắt pattern phức tạp / phi tuyến (ML win)
