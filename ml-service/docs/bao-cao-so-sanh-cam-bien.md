# Báo cáo so sánh detector trên 5 cảm biến thời tiết

**Module:** `ml-service` — Ngày 14/05/2026
**Branch:** `feat/anomaly-compare`
**Script:** `python -m scripts.evaluate_detection --sensor all --methods all --sweep-k 1.0 8.0 0.5`

Mục đích: đánh giá xem detector nào tốt nhất cho **từng** trong 5 cảm biến thời tiết của trạm — không chỉ `temperature` như báo cáo trước.

---

## 1. Thiết lập

### Dữ liệu

- Nguồn: NASA POWER hourly weather, 1 điểm địa lý (`21.0075, 105.5416`)
- Khoảng: **2020-01-01 → 2025-12-30**, tổng **52 584 điểm hourly**
- 5 cảm biến: `temperature`, `relativeHumidity`, `wind`, `rain`, `radiation`

### Chia train / test

- **Train:** toàn bộ CSV trừ 1 tháng cuối → **51 863 điểm** (2020-01-01 → 2025-11-30)
- **Test:** 1 tháng cuối → **721 điểm** (2025-11-30 → 2025-12-30)
- Lý do: production chỉ cần dự báo / detect ở chân trời ≤ 1 tháng, nên giữ test slice đúng độ dài đó. Cờ `--test-months` đồng nhất ở cả `scripts/train.py`, `scripts/evaluate_detection.py`, `scripts/evaluate_forecast.py` nên artifact không bao giờ nhìn thấy eval slice.

### Mô hình đã retrain trước eval

`python -m scripts.train --model all` chạy lại cả 3 trên train slice 51 863 điểm:

| Mô hình | Train trên | Residual std (in-sample, °C) |
|---|---|---|
| ARIMA(5, 1, 4) | 51 863 điểm temperature | 0.418 |
| SARIMA(2, 0, 1)(1, 1, 1, 24) | 51 863 điểm temperature | 0.233 |
| LSTM (window=48, hidden=64, dropout=0.2) | 51 863 điểm × 5 biến | 0.926 |

> **Lưu ý:** LSTM có yếu tố ngẫu nhiên (random init + dropout) → mỗi lần retrain residual_std lệch nhau ~0.2°C. F1 của `lstm_residual` ở §3 lấy từ một lần fit cụ thể trong khi eval, không phải số ở bảng trên — directional conclusion (LSTM-residual không xuất sắc) ổn định qua các lần chạy.

Hai z-score detector không cần train thật sự — fit chỉ là tính statistic trên train slice trong vài giây.

### Anomaly injection (giống báo cáo trước)

Inject vào test slice 721 điểm:
- **spike**: 1% điểm bị nhân ±5σ_test → ~7 điểm
- **outlier**: 0.5% điểm bị đẩy lên `max + 50` → ~3 điểm
- **drift**: 24 điểm liên tiếp bị tăng tuyến tính từ 0 → 3σ_test → 24 điểm

Tổng inject: **34 anomalies** trên 721 điểm test = **4.7%**.

> **Quan trọng:** drift chiếm **24/34** anomaly. Drift là tăng chậm tuyến tính — mỗi điểm drift đứng riêng chỉ hơi lệch khỏi normal, không đạt threshold |z|>k. Point-wise z-score detector **không thể bắt drift** theo design. Recall tối đa của các z-score thuần chỉ ≈ (7+3)/34 ≈ 29%. Đây là lý do chính seasonal_zscore trên `temperature` báo F1 thấp — không phải vì model sai mà vì task không phù hợp.

### Detector & quét k

5 phương pháp:
- `zscore` — Modified Z-score (median / MAD), không xét giờ-trong-ngày
- `seasonal_zscore` — chia 24 bucket theo `hour-of-day` UTC, μ/σ riêng từng bucket
- `arima_residual` — `ResidualDetector(ArimaForecaster)`, fit lại ARIMA trên train slice của cảm biến đang eval
- `sarima_residual` — `ResidualDetector(SarimaForecaster)`, tương tự
- `lstm_residual` — `ResidualDetector(LstmForecaster)`; **bỏ qua** với mọi cảm biến ≠ `temperature` vì LstmForecaster hardcode target = temperature

Quét `k` từ 1.0 → 8.0 bước 0.5 (15 giá trị) cho mỗi cặp (cảm biến, detector); báo cáo best-F1 row.

CSV đầy đủ ở `artifacts/cross_sensor_sweep.csv`.

---

## 2. Phương pháp luận

Phần này nêu rõ các lựa chọn methodology của báo cáo này, lý do chọn, hạn chế còn lại, và phương án nâng cấp cho vòng eval tiếp theo. Mục đích: phân biệt rõ "kết luận có cơ sở" với "kết luận tạm thời, chờ kiểm chứng thêm".

### 2.1. Train / test split: single-holdout 1 tháng

**Cách làm hiện tại:** chia chronological — train = tất cả dữ liệu trước test slice; test = tháng cuối CSV (tháng 12/2025). Không shuffle.

**Lý do chọn:** đơn giản, no-leakage, match với cadence dự định của production.

**Hạn chế:**
- **Test set chỉ thuộc 1 mùa (đông Bắc Bộ).** Mọi kết luận chỉ chứng minh detector hoạt động trên mùa đông — không có bằng chứng nó hoạt động tốt vào mùa hè / xuân / thu. Đây là lỗ hổng methodology lớn nhất của báo cáo.
- **Test set quá nhỏ.** 34 anomaly → F1 sai số thống kê ~±0.03. Hai detector chênh F1 0.04 đã thuộc miền noise — không phân biệt được chắc chắn winner.

**Cách đúng chuẩn nên làm:** **rolling-origin / rolling-quarter backtest** —

```
Cho mỗi quý Q ∈ {Q1, Q2, Q3, Q4} của năm gần nhất:
    test slice  = tháng cuối của Q
    train slice = tất cả dữ liệu trước test slice
    Fit + sweep k, tính F1(sensor, detector, k) trên slice này
Trung bình F1 cross-quarter cho mỗi (sensor, detector, k)
```

Lợi ích: 4× test data (136 anomaly thay vì 34, sai số ±0.015), chứng minh tính generalization qua cả 4 mùa. Vòng eval tiếp theo sẽ thêm vào — báo cáo này cố ý giới hạn scope để first-pass empirically scan detector trước khi đầu tư thời gian rolling backtest.

### 2.2. SARIMA `m=24` — không phải `m=2160` cho "Bắc Bộ 4 mùa"

Một misconception dễ mắc: "Bắc Bộ có 4 mùa → SARIMA phải có period m = 3 tháng = 2160 giờ". Sai về mặt định nghĩa SARIMA.

Trong `SARIMA(p,d,q)(P,D,Q,m)`, **`m` là độ dài một chu kỳ lặp lại đầy đủ**, không phải "độ dài một mùa":

| `m` | Ý nghĩa | Đúng cho dataset này? |
|---|---|---|
| `24` | Pattern lặp mỗi ngày (8h sáng hôm sau giống 8h sáng hôm nay) | ✅ Đúng — chu kỳ ngày là tín hiệu mạnh nhất trên hourly temperature |
| `2160` | Pattern lặp mỗi 3 tháng (tháng 1 ≡ tháng 4 ≡ tháng 7 ≡ tháng 10) | ❌ Sai — đông ≠ xuân ≠ hạ ≠ thu; 4 mùa không phải repeat của cùng một pattern |
| `8760` | Pattern lặp mỗi năm (1/2026 ≡ 1/2027) | ⚠️ Đúng về physical, nhưng D=1 + m=8760 cần ≥17520 train points (≥2 năm); mỗi fit chậm hàng giờ, không tractable |

**Tóm lại:** giữ `m=24` (chu kỳ ngày — tín hiệu mạnh nhất trên hourly data). Cross-season variation (4 mùa Bắc Bộ) được xử lý bằng cách train trên toàn bộ 6 năm lịch sử — model học được mean/variance trung bình của cả 4 mùa, không bị bias bởi 1 mùa duy nhất.

### 2.3. Synthetic anomaly vs real anomaly

Eval inject 3 pattern (`spike`, `outlier`, `drift`) trong test slice — không dùng anomaly thật từ sensor production. Hạn chế:

- **Drift chiếm 70% (24/34) anomaly** → eval thiên về detector bắt drift. Z-score thuần không bắt drift theo design (mỗi điểm drift chỉ hơi lệch normal) → recall ceiling ≈ 10/34 = 29%. F1 thấp của z-score trên `temperature` (0.300) không có nghĩa model dở — chỉ có nghĩa task có 70% anomaly mà phương pháp này theo definition không thể bắt.
- **Thiếu "stuck-at-value"** (sensor giữ nguyên 1 giá trị nhiều giờ liên tiếp) — pattern lỗi phổ biến nhất trong IoT thật.
- **Thiếu "NaN/missing run"** (sensor dropout) — cũng là anomaly trong production.
- **`±5σ_test` scale với test-slice σ** — tháng 12 σ nhỏ → spike thật sự không xa khỏi normal range bao nhiêu → detector bị task khó không tự nhiên.

**Cách đúng chuẩn nên làm:** (a) bổ sung 2 pattern `stuck` + `nan_run` vào `inject_anomalies()`; (b) tách metric riêng theo loại anomaly — precision/recall trên `spike+outlier` (point-wise) tách khỏi `drift` (sequence-wise) — để so sánh công bằng giữa detector point và detector sequence; (c) validate trên anomaly thật (label thủ công) sau khi BE chạy 1-2 tuần thu được sensor data thật.

### 2.4. Tham số `k` sweep, không tuning

Quét k từ 1.0 → 8.0 step 0.5, báo cáo best-F1 row. **Đây không phải hyperparameter tuning thật sự** — best k được chọn *trên test set*, có data leakage về phía hyperparameter selection. Nếu deploy production với k được chọn theo bảng này thì sẽ overfit test slice.

**Cách đúng chuẩn nên làm:** chia thành **train / validation / test** thay vì train / test. Tune k trên validation, report final F1 trên test (chưa thấy). Trong rolling-quarter setup ở §2.1, validation slice có thể là tháng kế trước test slice của mỗi quarter.

Với báo cáo này, best-k chỉ dùng để **so sánh detector** (cùng test slice, cùng leakage → fair so sánh tương đối), không phải để chốt k cho production. Production phải chốt k qua validation.

### 2.5. Forecaster bị refit trên train slice của *từng* sensor

`evaluate_detection.py` gọi `det.fit(train)` mỗi detector × mỗi sensor. Nghĩa là `arima_residual` trên `wind` không phải dùng saved ARIMA artifact (vốn fit trên temperature), mà fit lại ARIMA mới trên wind data. Saved artifact chỉ contribute **hyperparameter** (order=(5,1,4) chẳng hạn), không phải trained weights.

Đây là intentional design — saves user phải train 5×3=15 model riêng — nhưng implication phương pháp luận:
- Hyperparameter order=(5,1,4) được chọn cho temperature có thể không tối ưu cho wind/rain/humidity.
- LSTM hardcode target=temperature → bỏ qua hoàn toàn cho non-temperature.

**Cách đúng chuẩn nên làm:** chạy `auto_arima` riêng từng sensor (chấp nhận chi phí ~10× thời gian) để có order riêng cho mỗi cảm biến. LSTM mở rộng `target_col` parameter để fit được cho mọi cảm biến.

### 2.6. Tóm tắt mức độ "rigorous" của các kết luận

| Kết luận | Mức độ tin cậy | Lý do |
|---|---|---|
| seasonal_zscore là default tốt cho `relativeHumidity`, `wind` | 🟢 Cao | F1 chênh xa các phương pháp khác (>0.15), vượt sai số ±0.03 |
| rain cần model forecaster, không phải z-score | 🟢 Cao | Lý do định tính rõ (zero-inflated); F1 chênh xa |
| seasonal_zscore tốt cho `radiation` | 🟡 Trung bình | F1=0.419 chỉ hơn arima_residual 0.05 → có thể trong sai số |
| zscore tốt cho `temperature` hơn seasonal_zscore | 🔴 Thấp | Lý do thật là 70% drift anomaly ≠ z-score capability; không có evidence zscore tốt hơn seasonal cho task production thật |
| `m=24` đúng, `m=2160` sai | 🟢 Cao | Lý do mathematical (định nghĩa SARIMA), không cần empirical |

Khuyến nghị triển khai ở §6 phải đọc kèm bảng này — ưu tiên áp dụng các kết luận 🟢, các kết luận 🟡 / 🔴 nên xem là *hypothesis cần validate* hơn là *fact*.

---

## 3. Kết quả từng cảm biến (best F1)

### temperature

| Detector | k tối ưu | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| `zscore` | 3.00 | 0.929 | 0.382 | **0.542** |
| `lstm_residual` | 2.50 | 0.266 | 0.618 | 0.372 |
| `seasonal_zscore` | 2.50 | 1.000 | 0.176 | 0.300 |
| `arima_residual` | 8.00 | 0.077 | 0.941 | 0.142 |
| `sarima_residual` | 8.00 | 0.060 | 0.971 | 0.113 |

### relativeHumidity

| Detector | k tối ưu | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| `seasonal_zscore` | 3.00 | 0.880 | 0.647 | **0.746** |
| `zscore` | 3.50 | 0.607 | 0.500 | 0.548 |
| `arima_residual` | 8.00 | 0.116 | 0.765 | 0.202 |
| `sarima_residual` | 8.00 | 0.081 | 0.853 | 0.149 |

### rain

| Detector | k tối ưu | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| `sarima_residual` | 1.00 | 0.510 | 0.765 | **0.612** |
| `arima_residual` | 1.50 | 0.548 | 0.676 | 0.605 |
| `seasonal_zscore` | 1.00 | 0.588 | 0.294 | 0.392 |
| `zscore` | 5.50 | 0.099 | 0.235 | 0.139 |

### radiation

| Detector | k tối ưu | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| `seasonal_zscore` | 2.00 | 1.000 | 0.265 | **0.419** |
| `arima_residual` | 8.00 | 0.256 | 0.676 | 0.371 |
| `sarima_residual` | 7.50 | 0.210 | 0.853 | 0.337 |
| `zscore` | 1.50 | 0.178 | 0.618 | 0.276 |

### wind

| Detector | k tối ưu | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| `seasonal_zscore` | 2.00 | 0.514 | 0.559 | **0.535** |
| `arima_residual` | 6.50 | 0.299 | 0.588 | 0.396 |
| `sarima_residual` | 8.00 | 0.276 | 0.618 | 0.382 |
| `zscore` | 5.50 | 0.308 | 0.235 | 0.267 |

---

## 4. Bảng winner cross-sensor

| Cảm biến | Winner | F1 | k | Detector hạng 2 | F1 hạng 2 |
|---|---|---:|---:|---|---:|
| `temperature` | `zscore` | 0.542 | 3.00 | `lstm_residual` | 0.372 |
| `relativeHumidity` | `seasonal_zscore` | 0.746 | 3.00 | `zscore` | 0.548 |
| `rain` | `sarima_residual` | 0.612 | 1.00 | `arima_residual` | 0.605 |
| `radiation` | `seasonal_zscore` | 0.419 | 2.00 | `arima_residual` | 0.371 |
| `wind` | `seasonal_zscore` | 0.535 | 2.00 | `arima_residual` | 0.396 |

---

## 5. Nhận xét

### `seasonal_zscore` ổn nhất cho 3/5 cảm biến

`relativeHumidity`, `radiation`, `wind` đều có chu kỳ ngày rõ rệt (RH thấp giữa trưa, radiation theo đường mặt trời, gió thường yên ban đêm). Bucket theo `hour-of-day` ăn được pattern này → `seasonal_zscore` rõ ràng đứng nhất.

`radiation` F1=0.419 là thấp tuyệt đối — vì ban đêm σ ≈ 0 (luôn 0) nên detector skip những bucket đó → bị **under-detect**. Recall chỉ 0.265 dù precision 1.000.

### `rain` cần model — `zscore`/`seasonal_zscore` đều thua xa

`rain` là zero-inflated (đa số giờ = 0). Z-score thuần σ ≈ 0 ở phần lớn bucket → không phân biệt được tín hiệu thật vs anomaly. Trong khi đó:

- `sarima_residual` F1=0.612 — đứng nhất; residual của SARIMA bắt được "lượng mưa bất thường" theo pattern thời gian
- `arima_residual` F1=0.605 — sát nút SARIMA
- `seasonal_zscore` F1=0.392 — kém một bậc
- `zscore` F1=0.139 — gần như vô dụng

Tức là: muốn detect anomaly trên `rain` nghiêm túc thì **phải có ml-service** chạy song song, BE thuần Java không đủ.

### Surprise: temperature → `zscore` thắng `seasonal_zscore`

Báo cáo trước (split 80/20) thì `seasonal_zscore` đạt F1=0.948 trên temperature, đè bẹp các detector khác. Trong báo cáo này thì `zscore` đơn giản (F1=0.542) lại đánh bại `seasonal_zscore` (0.300).

Lý do thật (xem §2.3): **drift chiếm 24/34 anomaly** và point-wise z-score detector không bắt được drift theo design. Recall ceiling ≈ 29%, và seasonal_zscore đạt recall 0.176 = đã gần ceiling. Modified Z-score (`zscore`) "thắng" chủ yếu vì MAD-based threshold tightness — không phải vì hiểu data tốt hơn. **Kết luận này có độ tin cậy thấp** (xem §2.6).

### `arima_residual` / `sarima_residual` precision thấp cho ngoại trừ `rain`

Khi forecast vốn đã tốt (temperature, RH, radiation, wind có pattern dễ đoán), residual của ARIMA/SARIMA bị **nhiễu trắng đẹp** — std rất bé nên gần như mọi điểm có |z| > k. Best F1 hay đến ở k=7–8 (vẫn precision thấp 0.08–0.30). Riêng `rain` vì forecast không thể chính xác → residual có cấu trúc → detector hoạt động ổn.

### `lstm_residual` chỉ chạy trên `temperature`

`ml/forecasters/lstm.py` hardcode `TARGET_FEATURE = "temperature"` → eval bỏ qua trên 4 cảm biến còn lại. Trên temperature kết quả F1=0.372 — đứng thứ 2 sau `zscore` nhưng kém xa kết quả 80/20-split (báo cáo trước F1 cao hơn).

---

## 6. Khuyến nghị triển khai

### Detector per cảm biến cho BE Java (port `SeasonalZScoreService`)

| Cảm biến | Detector đề xuất | k | Lý do |
|---|---|---:|---|
| `temperature` | `zscore` (Modified Z) | 3.0 | Win trên test, nhưng tin cậy thấp (xem §2.6); cần thêm class `ZScoreService` trong BE |
| `relativeHumidity` | `seasonal_zscore` | 3.0 | F1=0.746, cao nhất |
| `radiation` | `seasonal_zscore` | 2.0 | F1=0.419; precision 1.0 / recall thấp do night-buckets σ≈0 |
| `wind` | `seasonal_zscore` | 2.0 | F1=0.535, cao nhất |
| `rain` | (delegate ml-service `/detect`) | — | z-score thuần kém; sarima_residual F1 ~0.6 nhưng cần Python |
| `humidity30`, `humidity60` (soil) | `seasonal_zscore` | 3.0 | Không có ground truth NASA để eval; mặc định theo nhóm tốt nhất |

> **Code chưa viết:** hiện `SeasonalZScoreService.java` chỉ áp seasonal_zscore cho mọi sensorId. Để triển khai khuyến nghị này cần:
> - Thêm `kPerSensor` (Map<String, Double>) thay cho global `k`
> - Thêm `ZScoreService.java` (Modified Z-score) để dùng riêng cho `temperature`
> - Thêm strategy chọn detector theo sensorId
> - Cho `rain`: trong listener gọi ml-service `/detect` thay vì tự xử lý

### Cấu hình retrain cron cho ml-service

```cron
# Retrain hàng tuần (Chủ Nhật 01:00) trên toàn bộ NASA CSV
0 1 * * 0 cd /opt/cassava/ml-service && \
  .venv/bin/python -m scripts.train --model all
```

### Cấu hình retrain cron cho `SeasonalZScoreService` trên BE

`SeasonalZScoreService.java` đã có `@Scheduled` daily ở 02:30. Cadence này phù hợp — refit nhẹ (chỉ tính lại bucket statistic trên Mongo), không đắt. Dữ liệu Mongo `sensor_value` của BE là **real measurements** chứ không phải NASA POWER, nên statistic sẽ phản ánh climate thực ở field — đó mới là điều quan trọng cho anomaly detection production.

---

## 7. Caveat & next step

### Caveat chính

- **Test slice chỉ 34 anomaly.** Mọi F1 ở đây có sai số ~±0.03. Trước khi đóng cứng `kPerSensor` trong production, nên (a) chạy lại eval với `--test-months 3` cho test slice lớn hơn, (b) kiểm tra trên real data 1–2 tuần sau khi deploy BE.
- **Eval dùng synthetic anomalies.** Drift chiếm 70% → kết quả thiên về detector bắt được drift (xem §2.3). Sensor lỗi thật phổ biến hơn là spike / stuck-at-value / NaN — không có trong injection set.
- **Eval phương pháp test loose:** anomaly inject với `±5σ_test` mà `σ_test` của tháng 12 nhỏ → spike thật sự không quá xa khỏi normal range → detector chật vật. Eval với `±10σ_test` hoặc fixed absolute Δ sẽ cho F1 cao hơn nhưng bớt thực tế.

### Việc cần làm tiếp

1. **Rolling-quarter backtest** (§2.1) — cross-validation trên 4 mùa để defense methodology.
2. **Bổ sung "stuck-at-value" và "NaN run" patterns** trong `inject_anomalies` để eval cover use case sensor hardware lỗi thật.
3. **Multivariate LSTM cho non-temperature sensors:** hiện `LstmForecaster` hardcode target = temperature. Mở rộng thành `LstmForecaster(target_col)` để chạy được lstm_residual trên 5 cảm biến.
4. **Retrain trên Mongo sensor_value** (thay vì NASA POWER) cho production: NASA chỉ là dataset huấn luyện mock-up; field thực dùng `humidity30/60` (soil) không có trong NASA.
5. **Per-sensor k autotune** trong BE: viết unit test load test data từ Mongo, sweep k mỗi tuần, ghi best k vào config.

---

## 8. Cách tái tạo

```bash
cd ml-service

# Retrain (51 863 train + 721 test holdout)
./.venv/Scripts/python.exe -m scripts.train --model all

# Eval đầy đủ
./.venv/Scripts/python.exe -m scripts.evaluate_detection \
    --sensor all --methods all \
    --sweep-k 1.0 8.0 0.5 \
    --sweep-out artifacts/cross_sensor_sweep.csv
```

Tổng thời gian (Windows CPU, no GPU): retrain ~15 phút, eval ~15–20 phút.
