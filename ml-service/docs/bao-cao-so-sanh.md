# Báo cáo so sánh mô hình phát hiện bất thường và dự báo nhiệt độ

**Module:** `ml-service` — Ngày 07/05/2026

## 1. Train mô hình

### Dữ liệu

- Lấy từ NASA POWER (hourly weather), giai đoạn **2020-01-01 → 2025-12-30** — tổng **52 584 điểm**.
- 5 biến: `temperature`, `relativeHumidity`, `wind`, `rain`, `radiation`.
- ARIMA/SARIMA chỉ dùng `temperature`. LSTM dùng cả 5 biến.
- Một lần fetch ra CSV (`scripts/fetch_nasa.py`), sau đó train từ file đó để khỏi gọi API mỗi lần.

### Mô hình train

| Mô hình | Cách chọn cấu hình | Order/kiến trúc cuối |
|---|---|---|
| **ARIMA** | `pmdarima.auto_arima` — stepwise search theo AIC | (5, 1, 4) |
| **SARIMA** | `statsforecast.AutoARIMA` (Cython, tránh OOM của statsmodels) | (2, 0, 1)(1, 1, 0, 24) |
| **LSTM** | Kiến trúc cố định | window=48, LSTM(64) → Dropout(0.2) → Dense(32) → Dense(1) |

> **`auto-order` và `AIC` là gì?**
> - **AIC** (Akaike Information Criterion) — chỉ số đo độ "tốt" của mô hình thống kê. Công thức: `AIC = 2k − 2·ln(L)` với `k` = số tham số, `L` = likelihood (xác suất mô hình sinh ra data thực). AIC **càng nhỏ càng tốt** — vừa thưởng cho fit tốt (`L` lớn) vừa phạt nếu nhiều tham số (`k` lớn) → tránh overfit.
> - **Stepwise search** — thử lần lượt nhiều bộ order khác nhau (vd. `(1,1,1)`, `(2,1,1)`, `(2,1,2)`...), tính AIC từng cái, chọn bộ có AIC nhỏ nhất. Là một dạng greedy search trên grid của order.
> - **`auto-order`** — chế độ trong `scripts/train.py` dùng stepwise search thay vì hardcode order. ARIMA dùng `pmdarima.auto_arima`, SARIMA dùng `statsforecast.AutoARIMA`. Bật bằng cờ `--auto-order` khi train.

Hai mô hình Z-score (Modified và Seasonal) không cần train thực sự — chỉ tính median/MAD và mean theo giờ-trong-ngày từ tập train, fit trong vài giây.

### Tham số cấu hình từng mô hình

**ARIMA** (`ml/forecasters/arima.py` → `ArimaForecaster`)

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `order = (p, d, q)` | `(2, 1, 2)` | Bậc AR / sai phân / MA. Khi `--auto-order` được dùng, sẽ thay bằng order tự chọn. |

Auto-search bounds (trong `scripts/train.py`): `max_p=5, max_q=5, max_d=2`. Order auto-selected hiện tại: **(5, 1, 4)**.

**SARIMA** (`ml/forecasters/sarima.py` → `SarimaForecaster`)

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `order = (p, d, q)` | `(1, 1, 1)` | Bậc ARIMA component (giờ) |
| `seasonal_order = (P, D, Q, m)` | `(1, 1, 1, 24)` | Bậc seasonal + chu kỳ. `m=24` = chu kỳ daily |
| `auto = True/False` | `False` | Khi `True`: dùng `statsforecast.AutoARIMA` stepwise search |

Auto-search bounds: `max_p=2, max_q=2, max_P=1, max_Q=1, max_d=1, max_D=1`. Order auto-selected hiện tại: **(2, 0, 1)(1, 1, 0, 24)**.

**LSTM** (`ml/forecasters/lstm.py` → `LstmForecaster`)

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `window` | `48` | Số giờ context input |
| `epochs` | `30` | Số epoch huấn luyện |
| `batch_size` | `32` | Batch size |

*Giải thích chi tiết:*

- **`window = 48`** — số giờ trong quá khứ được đưa vào model để dự báo giờ tiếp theo. Chọn 48 vì cần ≥ 2 chu kỳ daily (24h) để model học được "hôm nay khác hôm qua như thế nào". `window` quá nhỏ (vd. 12) → thiếu bối cảnh chu kỳ, `window` quá lớn (vd. 168) → tăng số tham số và overfitting trên 52k điểm.
- **`epochs = 30`** — số lần model đi qua toàn bộ tập train. Quá ít → underfit (loss chưa hội tụ), quá nhiều → overfit. 30 là điểm cân bằng thực nghiệm với dataset này; loss giảm chậm dần và phẳng quanh epoch 25-30. Hiện chưa cài early stopping — số epoch hardcoded.
- **`batch_size = 32`** — số mẫu dùng để cập nhật weights mỗi bước. Nhỏ (8-16) → cập nhật nhiều lần hơn, training stochastic hơn (thoát local minima dễ hơn) nhưng chậm. Lớn (128-256) → nhanh trên GPU nhưng smooth quá, dễ kẹt minima. 32 là default phổ biến phù hợp khi train CPU.

*Kiến trúc network* (cố định trong `LstmForecaster.fit()`):

```
Input(48, 5)              # 48 giờ × 5 biến (T, RH, wind, rain, radiation)
   ↓
LSTM(64)                   # 64 hidden units, return chỉ giá trị cuối
   ↓
Dropout(0.2)               # bỏ ngẫu nhiên 20% output khi train, chống overfit
   ↓
Dense(32, relu)            # projection phi tuyến trung gian
   ↓
Dense(1)                   # 1 neuron output = nhiệt độ giờ kế (scaled 0-1)
```

Giải thích từng tầng:

- **`LSTM(64)`** — 64 đơn vị ẩn trong lớp LSTM, quyết định độ "thông minh" của mạng. Nhiều hơn (128, 256) → model học được pattern phức tạp hơn nhưng tăng tham số (~70k → 280k) và rủi ro overfit. 64 đủ cho 5 feature × 48 timestep mà không quá lớn.
- **`Dropout(0.2)`** — trong lúc train, ngẫu nhiên bỏ 20% output của LSTM, giúp model không phụ thuộc quá mức vào một số neuron cụ thể. Khi inference (predict) thì tắt dropout, dùng đầy đủ.
- **`Dense(32, relu)`** — tầng fully-connected 32 neuron với activation ReLU, thêm non-linearity giữa output LSTM và prediction cuối. Bỏ tầng này → model giảm capacity; tăng lên (64, 128) → tăng tham số nhỏ.
- **`Dense(1)`** — 1 output cuối: dự báo nhiệt độ giờ tiếp theo ở dạng scaled (0-1). Khi inference, dùng `MinMaxScaler.inverse_transform` để đưa về °C.

*Các thiết lập training khác* (đã hardcode trong `fit()`):

- **Optimizer**: Adam (learning rate mặc định 0.001).
- **Loss**: MSE (Mean Squared Error) — phù hợp cho regression.
- **Scaler**: `MinMaxScaler` (từ thư viện `scikit-learn`) — chuẩn hóa từng feature về dải `[0, 1]` theo công thức `(x - min) / (max - min)`. `min` và `max` được tính trên tập train rồi áp dụng nguyên cho test/inference. Lý do dùng: LSTM hội tụ nhanh hơn khi mọi feature có cùng scale; tránh feature giá trị lớn (vd. `radiation` 0–30 MJ/m²/h) lấn át feature giá trị nhỏ (vd. `wind` 0–10 m/s) trong quá trình tính gradient. Khi predict, đầu ra của LSTM là giá trị đã scaled (nằm trong [0,1]), phải dùng `scaler.inverse_transform()` để đưa về °C thực.

Để đổi bất cứ tham số nào ngoài 3 cái ở bảng (`window/epochs/batch_size`), phải sửa trực tiếp `LstmForecaster.__init__()` (kiến trúc, scaler) hoặc `fit()` (optimizer, loss, callback).

**Modified Z-score** (`ml/detectors/zscore.py` → `ZScoreDetector`)

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `window` | `60` | Kích thước rolling buffer (số điểm gần nhất dùng làm reference) |

**Seasonal Z-score** (`ml/detectors/seasonal_zscore.py` → `SeasonalZScoreDetector`)

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `_MIN_PER_BUCKET` | `10` | Số mẫu tối thiểu mỗi bucket giờ-trong-ngày để tính được μ, σ |

### Lệnh train

```bash
# Fetch dữ liệu (1 lần). Tham số: --start, --end, --lat, --lon, --output
python -m scripts.fetch_nasa
python -m scripts.fetch_nasa --start 2018-01-01 --end 2024-12-30 \
    --output artifacts/nasa/custom.csv

# Train với cấu hình mặc định (order hardcoded)
python -m scripts.train --model arima
python -m scripts.train --model sarima
python -m scripts.train --model lstm
python -m scripts.train --model all

# Train với auto-order (khuyến nghị cho ARIMA/SARIMA)
python -m scripts.train --model arima --auto-order
python -m scripts.train --model sarima --auto-order

# Tuỳ chọn dữ liệu khác
python -m scripts.train --model all --data artifacts/nasa/custom.csv

# Giới hạn search trên N điểm cuối cùng (chỉ ảnh hưởng pmdarima của ARIMA)
python -m scripts.train --model arima --auto-order --auto-search-size 10000
```

Artifact lưu vào `artifacts/{arima.pkl, sarima.pkl, lstm/}`. Mỗi artifact bao gồm cả order/scaler/residual_std → khi load lại để evaluate hoặc deploy, các tham số này được khôi phục tự động.

## 2. Cách so sánh

Chia chronological 80/20: **train 42 067 điểm cũ** / **test 10 517 điểm mới**. Mọi mô hình **refit trên train slice** trước khi đánh giá để tránh data leakage.

> **`train slice`, `test slice`, `refit` là gì?**
> - **`train slice`** = phần dữ liệu dùng để huấn luyện (80% đầu = 42 067 điểm cũ nhất).
> - **`test slice`** = phần dữ liệu để kiểm thử, không cho mô hình thấy lúc train (20% cuối = 10 517 điểm mới nhất).
> - **`refit`** = train lại mô hình từ đầu trên `train slice`, **không** dùng artifact đã có sẵn trên đĩa. Lý do: artifact gốc được train trên *toàn bộ* chuỗi (cả phần test) để có hiệu suất tốt nhất khi deploy production. Nếu evaluate luôn artifact đó thì vi phạm "data leakage" — mô hình đã "thấy tương lai", metric không còn ý nghĩa.
>
> Sau refit, mô hình chỉ biết `train slice`. Đem nó đi đoán `test slice` mới phản ánh đúng khả năng tổng quát hóa khi gặp dữ liệu chưa từng thấy.

### So sánh phát hiện bất thường

Test slice không có anomaly thật → tự inject 3 loại để có ground truth:

- **Spike**: 1% điểm cộng ±5σ ngẫu nhiên
- **Outlier**: 0.5% điểm thay bằng `max + 50°C`
- **Drift**: 24 điểm liên tiếp tăng tuyến tính 0 → 3σ

Tổng **181 / 10 517 điểm test (1.7%)** được đánh nhãn anomaly.

Sau đó với mỗi detector, đi qua từng điểm test, tính score thô `|residual| / σ`, rồi sweep ngưỡng `k` để tìm điểm cân bằng tốt nhất.

> **`residual` và `σ` là gì?**
> - **`residual`** = sai số dự báo, tính bằng `residual = actual - predicted`. Tức là giá trị thực trừ giá trị mô hình đoán. Cao = mô hình đoán sai nhiều.
> - **`σ`** (sigma, đọc là "xích-ma") = độ lệch chuẩn của `residual` đo trên tập **training**. Đại diện cho mức "sai số bình thường" mà mô hình hay mắc phải.
> - **`|residual| / σ`** = sai số chuẩn hóa. Bằng 1 nghĩa là sai như mức bình thường; bằng 5 nghĩa là sai gấp 5 lần bình thường → khả năng cao là điểm bất thường. Đây chính là score mà k được so sánh với.
>
> **Ngưỡng `k` là gì?** Đó là số nhân độ lệch chuẩn dùng để quyết định một điểm có bất thường hay không. Luật của mọi detector:
>
> ```
> is_anomaly = (score > k)
> ```
>
> với `score = |residual| / σ` (hoặc `|z|` đối với Z-score). `k` càng lớn = ngưỡng càng chặt → ít flag → precision tăng nhưng recall giảm. `k` càng nhỏ = lỏng → flag nhiều hơn → recall tăng nhưng precision giảm. Sweep `k` từ 1.0 → 10.0 (step 0.5) cho từng giá trị tính Precision/Recall/F1, sau đó chọn `k` cho F1 cao nhất — gọi là **`best k`**.

> **3 chỉ số đánh giá phát hiện bất thường:**
>
> Trong test slice có 181 điểm thật sự là anomaly (đã đánh nhãn lúc inject) và 10 336 điểm bình thường. Detector của ta đưa ra dự đoán cho từng điểm: hoặc **flag** (cho là anomaly), hoặc **không flag** (cho là bình thường). Quy ước đặt tên:
>
> - **Positive** (P) = "kết luận có anomaly" — kết quả mà detector quan tâm. Trong bài toán phát hiện bất thường, anomaly là cái cần tìm nên gọi nó là *positive*.
> - **Negative** (N) = "kết luận bình thường" — kết quả còn lại.
> - **True** (T) = dự đoán **trùng** với sự thật.
> - **False** (F) = dự đoán **sai** so với sự thật.
>
> Ghép 4 trường hợp ra "ma trận nhầm lẫn" (confusion matrix):
>
> | | Detector flag (Positive) | Detector không flag (Negative) |
> |---|---|---|
> | **Sự thật: là anomaly** | **TP** (True Positive) — bắt đúng | **FN** (False Negative) — bỏ sót |
> | **Sự thật: bình thường** | **FP** (False Positive) — báo nhầm | **TN** (True Negative) — đúng là bình thường |
>
> *(TN không xuất hiện trong precision/recall vì khi anomaly chỉ chiếm ~1.7%, đa số điểm đều là TN — số này lớn mặc định, không phản ánh chất lượng phát hiện.)*
>
> Từ confusion matrix:
> - **Precision** = `TP / (TP + FP)` = trong số điểm detector flag, bao nhiêu **% là anomaly thật**. Cao = ít báo nhầm. `Precision = 1.0` nghĩa là không có false positive nào.
> - **Recall** = `TP / (TP + FN)` = trong số anomaly thật, detector bắt được **bao nhiêu %**. Cao = ít bỏ sót. `Recall = 1.0` nghĩa là bắt hết.
> - **F1** = `2 × precision × recall / (precision + recall)` = trung bình hài hòa của precision và recall. Cao = vừa ít nhầm vừa ít sót. Là chỉ số duy nhất để chọn `best k` (chỉ tối ưu precision sẽ làm sụp recall và ngược lại).

```bash
python -m scripts.evaluate_detection --methods all --sweep-k 1.0 10.0 0.5
```

5 phương pháp được so:
1. Modified Z-score
2. Seasonal Z-score (theo giờ-trong-ngày)
3. ARIMA + ngưỡng residual
4. SARIMA + ngưỡng residual
5. LSTM + ngưỡng residual

### So sánh dự báo

Đánh giá riêng 3 forecaster (ARIMA, SARIMA, LSTM) trên test slice **không inject** — đây là chất lượng dự báo thuần.

> **Horizon `h` là gì?** Là số bước (giờ) mô hình dự báo về phía trước tính từ thời điểm hiện tại. Ví dụ tại 10:00 sáng:
> - `h = 1` → dự báo giá trị lúc **11:00 sáng** (1 giờ sau)
> - `h = 6` → dự báo giá trị lúc **16:00 chiều** (6 giờ sau)
> - `h = 24` → dự báo giá trị lúc **10:00 sáng hôm sau** (24 giờ sau)
>
> `h` càng lớn = dự báo càng xa = càng khó. Báo cáo đánh giá ở 3 horizon đại diện cho 3 use case khác nhau:
> - **h = 1** — dự báo tức thời (vd. quyết định bật bơm trong giờ tới)
> - **h = 6** — kế hoạch nửa ngày
> - **h = 24** — lập lịch tưới tiêu cho 24 giờ kế tiếp

Online walk: tại mỗi timestamp, dự báo cho horizon h ∈ {1, 6, 24}, so với giá trị thực, rồi `update()` mô hình bằng giá trị thực để đi tiếp. Cap 2 000 walk starts/mô hình. Metric: **MAE, RMSE, MAPE**.

> **`online walk` là gì?** Là cách đánh giá mô hình bằng cách "đi" theo thứ tự thời gian qua tập test, mỗi bước **vừa dự báo vừa cập nhật state**. Cụ thể tại mỗi timestamp `t`:
>
> 1. Gọi `predict(t, h)` → mô hình trả `h` giá trị dự báo cho `t+1, t+2, ..., t+h`.
> 2. So các dự báo đó với giá trị thực tương ứng → đo sai số.
> 3. Gọi `update(actual_t, t)` để mở rộng state mô hình bằng giá trị thực — bước tiếp theo mô hình đã "biết" giá trị tại `t`.
> 4. Sang `t+1`, lặp lại.
>
> Khác với "batch predict" (dự báo cả test một phát rồi so) ở chỗ: online walk mô phỏng đúng kịch bản production — model luôn được cập nhật bằng giá trị mới nhất khi nó về tới. Cho metric phản ánh đúng cách model sẽ hoạt động khi deploy.

> **3 chỉ số đánh giá dự báo:**
>
> - **MAE** (Mean Absolute Error — sai số tuyệt đối trung bình) = `(1/n) × Σ|forecast - actual|`. Đơn vị giống giá trị gốc (°C). Dễ hiểu: "trung bình mô hình sai 5°C".
> - **RMSE** (Root Mean Square Error — căn bậc 2 trung bình bình phương sai số) = `√[(1/n) × Σ(forecast - actual)²]`. Cùng đơn vị MAE nhưng *phạt nặng các sai số lớn* (vì bình phương). Khi `RMSE/MAE > 1.3` → có một số điểm sai bất thường lớn kéo trung bình lên. RMSE = MAE khi mọi sai số bằng nhau.
> - **MAPE** (Mean Absolute Percentage Error — sai số tuyệt đối trung bình theo %) = `(1/n) × Σ(|forecast - actual| / |actual|) × 100%`. Không có đơn vị (chỉ là %). Cho phép so sánh giữa các thang khác nhau (vd. dự báo nhiệt độ vs độ ẩm). Hạn chế: phình to khi `actual` gần 0.
>
> Ví dụ: dự báo 28°C nhưng thực tế 30°C → residual = 2°C, |residual| = 2, |residual|/actual = 6.7%. Đo qua 1000 điểm rồi lấy trung bình ra MAE/RMSE/MAPE.

```bash
python -m scripts.evaluate_forecast --methods arima,sarima,lstm \
    --horizons 1 6 24 --max-points 2000
```

## 3. Kết quả

### Phát hiện bất thường (best k)

| Detector | Best k | Precision | Recall | F1 |
|---|---:|---:|---:|---:|
| **seasonal_zscore** | **3.0** | 1.000 | 0.901 | **0.948** |
| **zscore** | **5.0** | 1.000 | 0.845 | **0.916** |
| lstm_residual | 10.0 | 0.332 | 0.867 | 0.480 |
| arima_residual | 10.0 | 0.032 | 0.994 | 0.062 |
| sarima_residual | 10.0 | 0.024 | 1.000 | 0.046 |

**Nhận xét:**
- Phương pháp thống kê (Z-score) thắng tuyệt đối, **F1 tới 0.948**.
- ARIMA/SARIMA-residual gần như không phát hiện được — chúng flag mọi điểm vì σ residual lúc train không khớp với độ lệch chuẩn thật khi test, ngưỡng k mất tác dụng.
- LSTM nhờ window 48h multivariate nên đỡ hơn, F1 = 0.48, nhưng vẫn cách xa Z-score.

### Dự báo nhiệt độ

| Mô hình | Horizon (h) | MAE (°C) | RMSE (°C) | MAPE (%) |
|---|---:|---:|---:|---:|
| ARIMA(5,1,4) | 1 | 5.50 | 6.71 | 37.6 |
| ARIMA(5,1,4) | 6 | 7.11 | 8.38 | 48.1 |
| ARIMA(5,1,4) | 24 | 7.16 | 8.43 | 49.0 |
| SARIMA(2,0,1)(1,1,0,24) | 1 | 5.36 | 6.55 | 36.7 |
| SARIMA(2,0,1)(1,1,0,24) | 6 | 6.73 | 8.00 | 45.7 |
| SARIMA(2,0,1)(1,1,0,24) | **24** | **5.64** | **6.87** | **39.2** |
| **LSTM** (5 features, 48h) | **1** | **1.75** | **1.99** | **8.7** |
| LSTM | 6 | 9.30 | 10.52 | 46.3 |
| LSTM | 24 | 10.02 | 11.13 | 50.3 |

**Nhận xét:**
- **LSTM thắng tuyệt đối ở h = 1**: MAE 1.75°C — gấp 3 lần độ chính xác so với ARIMA/SARIMA. MAPE 8.7% là con số duy nhất một chữ số trong toàn bảng.
- **LSTM sụp đổ ở h ≥ 6** vì cách roll window đa bước hiện tại giữ nguyên 4 feature ngoài temperature, gây drift out-of-distribution. Cần seq2seq để khắc phục.
- **SARIMA thắng ở h = 24** nhờ thành phần seasonal lag m = 24 — về bản chất là "cùng giờ ngày mai giống cùng giờ hôm nay". ARIMA không có seasonal nên drift về long-run mean.
- ARIMA và SARIMA đều là forecaster yếu so với LSTM (h=1) — MAE 5–7°C trên chuỗi biên độ ~10°C/ngày là gần như "trung bình cục bộ".

## 4. Kết luận

- **Phát hiện ≠ Dự báo**: hai bài toán cần hai loại mô hình khác nhau.
  - Phát hiện cần *robust* với outlier → Z-score thắng.
  - Dự báo cần *bám tín hiệu* → LSTM/SARIMA thắng.
- **Đề xuất production:**
  - Phát hiện streaming: **Seasonal Z-score** (k = 3).
  - Dự báo h = 1: **LSTM**.
  - Dự báo h ≥ 6: **SARIMA**.
- ARIMA và SARIMA-residual giữ trong báo cáo như **kết quả tiêu cực** có giá trị: chứng minh paradigm "forecast → residual → threshold" không hoạt động khi σ in-sample không khớp σ ngoài sample.

---

*Code: [`ml/detectors/`](../ml/detectors/), [`ml/forecasters/`](../ml/forecasters/). Số liệu raw: [`artifacts/k_sweep_refactored.csv`](../artifacts/k_sweep_refactored.csv), [`artifacts/forecast_arima_sarima.csv`](../artifacts/forecast_arima_sarima.csv), [`artifacts/forecast_lstm.csv`](../artifacts/forecast_lstm.csv).*

*Chuyển sang .docx: `pandoc bao-cao-so-sanh.md -o bao-cao-so-sanh.docx`, hoặc copy-paste vào Word.*
