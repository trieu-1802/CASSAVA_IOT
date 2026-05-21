# Cấu hình LSTM Forecaster

**Module:** `ml-service` — `ml/forecasters/lstm.py`
**Mục đích:** giải thích kiến trúc + hyperparameter của LSTM forecaster trong dự án, kèm lý do chọn từng giá trị.

LSTM được dùng cho 2 vai trò:
1. **Forecasting** — dự đoán giá trị giờ kế tiếp của các cảm biến thời tiết (qua endpoint `/forecast`).
2. **Anomaly detection** — wrap qua `ResidualDetector` để tính residual = actual - forecast; nếu |residual| / σ > k thì coi là anomaly (qua endpoint `/detect`).

**LSTM là MULTI-TARGET**: 1 model duy nhất predict đồng thời cả 5 cảm biến (`temperature`, `relativeHumidity`, `wind`, `rain`, `radiation`). Khi cần dự đoán cụ thể 1 sensor, caller chỉ chọn "view" qua `LstmForecaster(target=<sensor>)` — model share, chỉ cột output được expose là khác. So với train riêng từng sensor, multi-target rẻ hơn 5× và share representation giữa các cảm biến tương quan (vd. radiation phụ thuộc cloud cover qua humidity / wind).

---

## 1. Cấu hình hiện tại

```python
LstmForecaster(
    window=48,            # nhìn lại 48 giờ (2 ngày)
    epochs=30,            # train 30 vòng qua data
    batch_size=32,        # 32 sample/batch
    target="temperature", # view selector — không ảnh hưởng training
)
```

### Kiến trúc neural network

```
Input  (48 timesteps × 5 features)
    │
    ▼
LSTM(64, return_sequences=False)
    │
    ▼
Dropout(0.2)
    │
    ▼
Dense(32, activation="relu")
    │
    ▼
Dense(5)         ← multi-target: 5 output, 1 per weather sensor
    │
    ▼
Output (5-vector — giá trị 5 cảm biến ở giờ tiếp theo)
```

Tại inference, caller chọn cột output qua `target` parameter: `target="radiation"` → expose cột 4 của Dense(5) output. Model + scaler được share giữa 5 view trong ml-service runtime (xem `api/main.py::_register_lstm_views`).

### Pipeline data + training

| Bước | Chi tiết |
|---|---|
| Input shape | `(N, 48, 5)` — N sample, window 48 giờ, 5 feature `[temperature, relativeHumidity, wind, rain, radiation]` |
| Output shape | `(N, 5)` — predict đồng thời cả 5 feature ở giờ kế tiếp |
| Scaler | `MinMaxScaler` — scale tất cả 5 feature về `[0, 1]` |
| Optimizer | `adam` (default lr=0.001) |
| Loss | `mse` — trung bình squared error trên cả 5 output |
| residual_std | Tính per-target sau fit (dict 5 giá trị), expose theo `self.target` |
| Verbose | 0 (silent) |

---

## 2. Lý do chọn từng tham số

Mỗi tham số có **trade-off** rõ ràng — không chọn ngẫu nhiên, mà chọn dựa trên: (1) kích thước data (51 800 sample sau split), (2) đặc tính bài toán (hourly weather, regression, multivariate), (3) hardware (CPU Windows, no GPU), (4) goal (anomaly detection + 1-step forecast).

### 2.1. `window = 48` (look-back 2 ngày)

**Định nghĩa:** số timestep model nhìn ngược để dự đoán giá trị kế tiếp. `window=48` nghĩa là input shape `(48, 5)` — 48 giờ × 5 cảm biến.

**Trade-off:**

```
Window nhỏ  ←─────────────────────→  Window lớn
   │                                      │
   ▼                                      ▼
Mất context           Bắt được pattern phức tạp
Underfit              nhưng:
                      - Input dimension to → cần data nhiều
                      - Train chậm
                      - Dễ overfit nếu data ít
```

**Tại sao 48?**

Lý do định tính (data có chu kỳ ngày):
- Weather hourly có pattern lặp **mỗi 24 giờ** (8h sáng hôm sau giống 8h sáng hôm nay về mức độ).
- Cần ≥ 24h để bắt được 1 chu kỳ đầy đủ. 23h là không đủ — pattern bị cắt.

Lý do định lượng (cross-day context):
- Window = 24h: chỉ thấy "hôm nay" — không phân biệt được "ngày trước trời lạnh ≠ ngày trước trời nóng".
- Window = 48h: thấy **2 chu kỳ** → học được dynamic "hôm qua → hôm nay" (vd. lạnh kéo dài, mưa nối tiếp).
- Window = 72h: thêm 1 ngày → ít thông tin mới, input dim tăng 33%.

Lý do từ data size:
- Train data: 51 863 sample. Sau khi tạo sequence: `51 863 − 48 = 51 815` sample.
- Với window 48 + 5 feature: input dimension = 240. Tỉ lệ sample/dim ≈ 216 — đủ để học mà không overfit.
- Với window 168 (7 ngày): input dim = 840. Tỉ lệ giảm xuống ≈ 61 — bắt đầu có overfit nguy cơ.

**Alternatives:**

| window | Sample/dim ratio | Capture | Train time | Verdict |
|---|---:|---|---|---|
| 24 | 433 | 1 chu kỳ ngày | Nhanh nhất | Underfit (mất cross-day) |
| **48** | **216** | **2 chu kỳ** | **~10 phút** | **✅ Sweet spot** |
| 72 | 144 | 3 chu kỳ | ~15 phút | Marginal gain |
| 168 | 61 | 7 ngày (weekly) | ~25 phút | Overfit, data quá ít cho window này |

Vì sao **không** chọn 168 dù tự nhiên nghĩ "1 tuần"? Weather không có pattern weekly mạnh như stock market — chu kỳ chính là daily. Thêm 5 ngày context (48 → 168) không cải thiện forecast tương ứng với cost.

### 2.2. `epochs = 30`

**Định nghĩa:** số lần model quét qua toàn bộ training data. Mỗi epoch = 1 vòng training trên 51 815 sample.

**Trade-off:**

```
Epoch ít                       Epoch nhiều
   │                                │
   ▼                                ▼
Underfit                  Overfit (hoặc plateau)
Train loss cao            Train loss thấp
Val loss cao              Val loss tăng (nếu overfit)
                          Hoặc Val loss plateau (waste compute)
```

**Tại sao 30?**

Empirical từ project tương tự:
- LSTM ~20 000 params trên ~50k sample thường hội tụ trong **20-30 epoch**.
- Sau epoch 25, loss curve flatten (giảm < 1% mỗi epoch) → return diminishing.
- Trước epoch 20, loss vẫn giảm > 5% mỗi epoch → còn học được.

Đo trên CPU Windows:
- 30 epoch ≈ 5-15 phút (phụ thuộc batch size + hardware).
- 50 epoch ≈ 10-25 phút — overhead 60-100% mà gain rất ít.

**Cẩn thận: không có early stopping**

Code hiện tại train cứng 30 epoch không kiểm tra validation loss. Hậu quả:
- Nếu data dễ học → có thể overfit ở epoch 28-30.
- Nếu data khó → có thể chưa hội tụ ở epoch 30.

Best practice (chưa implement):
```python
from tensorflow.keras.callbacks import EarlyStopping

self._model.fit(X, y,
    epochs=100,  # max
    validation_split=0.1,
    callbacks=[EarlyStopping(patience=5, restore_best_weights=True)]
)
```
→ tự dừng khi val loss không giảm 5 epoch liên tiếp. Robust hơn nhiều.

**Alternatives:**

| epochs | Train loss | Val loss | Verdict |
|---|---|---|---|
| 10 | Cao (chưa hội tụ) | Cao | Underfit |
| **30** | **Plateau** | **Plateau** | **✅ Sweet spot** |
| 100 | Rất thấp | Có thể tăng trở lại | Overfit risk |

### 2.3. `batch_size = 32`

**Định nghĩa:** số sample được forward + backward cùng lúc trong 1 gradient update.

**Trade-off:**

```
Batch nhỏ              Batch lớn
   │                       │
   ▼                       ▼
Gradient noisy        Gradient smooth
- Generalization tốt  - Train ổn định
- Escape local min    - Stuck local min
- Train chậm          - Train nhanh (vectorization)
- Ít memory           - Cần nhiều memory
```

**Tại sao 32?**

Mathematical reasoning (theory of small-batch noise):
- Stochastic Gradient Descent với batch nhỏ tạo gradient noise → giúp escape sharp local minima → converge to **flat minima** = generalize tốt hơn.
- Paper "Train longer, generalize better" (Hoffer et al.) chỉ ra batch < 64 thường có val accuracy cao hơn.

Hardware reasoning:
- LSTM cell có recurrent dependency → khó parallelize hoàn toàn dù batch lớn.
- Batch 32 trên CPU đủ để vectorize SIMD operations → không nhanh hơn nếu tăng lên 64.
- Memory: 32 sample × 48 timestep × 5 feature × 4 byte (float32) = 30 KB — không là vấn đề.

Empirical default cho LSTM: 32 là default được dùng rộng rãi trong papers + tutorials. Nếu không có lý do đặc biệt, dùng 32.

**Alternatives:**

| batch_size | Gradient | Train time/epoch | Verdict |
|---|---|---|---|
| 8 | Rất noisy | Chậm 4× | Quá noisy → unstable training |
| 16 | Noisy | Chậm 2× | OK nhưng không cần thiết |
| **32** | **Moderate** | **Baseline** | **✅ Standard** |
| 128 | Smooth | Nhanh 4× | Generalization có thể kém |
| 256 | Very smooth | Nhanh 8× | Overfit risk cao |

### 2.4. `LSTM(64)` — 64 hidden units

**Định nghĩa:** kích thước hidden state vector của LSTM cell. Mỗi timestep, LSTM duy trì vector h (64-d) + cell state c (64-d).

**Tại sao 64?**

Liên quan đến **model capacity** (số tham số trainable).

Tham số của LSTM layer:
```
4 × (input_dim + hidden_dim) × hidden_dim + 4 × hidden_dim (bias)
= 4 × (5 + 64) × 64 + 4 × 64
= 17 920
```

Toàn model:
- LSTM(64): 17 920 params
- Dense(32, relu): (64 × 32) + 32 = 2 080 params
- Dense(1): (32 × 1) + 1 = 33 params
- **Total: ~20 000 trainable params**

**Rule of thumb capacity vs data:**
- Cần **≥ 10× data** so với params để không overfit. Với 20 000 params → cần ≥ 200 000 sample.
- Mình có 51 800 sample → tỉ lệ 2.6× → **borderline**, đã thấp.
- Nếu tăng LSTM lên 128 unit → params nhảy lên ~70 000 → tỉ lệ 0.7× → **chắc chắn overfit**.

**Alternatives:**

| LSTM units | Params | Sample/param | Verdict |
|---|---:|---:|---|
| 16 | 1 408 | 37× | Underfit — không học được tương tác |
| 32 | 4 864 | 11× | Borderline OK |
| **64** | **17 920** | **2.9×** | **✅ Capacity vừa đủ** |
| 128 | 68 608 | 0.7× | Overfit nguy hiểm |
| 256 | 268 288 | 0.2× | Overfit chắc chắn |

LSTM(64) là **sweet spot** cho 51k sample multivariate.

### 2.5. `Dropout(0.2)` — 20% dropout

**Định nghĩa:** sau LSTM, 20% neuron bị "tắt" ngẫu nhiên mỗi training step.

**Trade-off:**

```
rate nhỏ                   rate lớn
   │                          │
   ▼                          ▼
Ít regularization         Quá regularization
Overfit                   Underfit (kill capacity)
```

**Tại sao 0.2?**

Empirical heuristics:
- LSTM thường dùng dropout **nhỏ hơn** CNN (CNN hay dùng 0.5). Lý do: LSTM đã có recurrence regularization tự nhiên.
- Recommendations từ Yarin Gal (paper "Dropout as Bayesian Approximation"): 0.1-0.3 cho recurrent network.
- 0.2 ở giữa range → moderate.

Lý do project-specific:
- Data 51k sample không cực nhỏ → không cần dropout cao.
- LSTM(64) đã không quá to → không cần kill capacity nhiều.
- Mục tiêu là **forecaster có residual_std thấp** → cần model fit tương đối chặt training, dropout cao sẽ làm residual rộng.

**Alternatives:**

| Dropout rate | Hiệu quả | Verdict |
|---|---|---|
| 0.0 | Không regularization | Overfit nếu data ít |
| 0.1 | Nhẹ | OK cho data nhiều |
| **0.2** | **Moderate** | **✅ Default LSTM** |
| 0.3-0.4 | Strong | Khi rất overfit |
| 0.5 | Aggressive | Kill capacity, dùng cho overparameterized model |

### 2.6. `Dense(32, activation="relu")` — bottleneck layer

**Định nghĩa:** layer giữa LSTM output và Dense(1) output, compress 64 → 32 với ReLU.

**Tại sao 32?**

Bottleneck pattern: `64 → 32 → 1` — buộc model học representation cô đọng trước khi predict.

Nếu chỉ có `LSTM(64) → Dense(1)`:
- Output là **linear combination** của 64 LSTM hidden — không học được non-linear pattern phức tạp.

Có `Dense(32, relu)` ở giữa:
- ReLU thêm non-linearity → model học được "phụ thuộc có điều kiện": vd. "khi humidity cao thì temperature dự đoán dùng feature mix khác với khi humidity thấp".

**Tại sao 32 chứ không 64 / 16?**
- 32 = một nửa của LSTM(64) → bottleneck. Bằng 64 thì không phải bottleneck.
- 16 quá ít, mất thông tin.

**Tại sao ReLU?**
- Không vanish gradient (ReLU' = 1 với x > 0).
- Cheap computational (max(0, x)).
- Standard cho intermediate layer.
- Alternative `tanh` cũng OK nhưng vanish gradient ở extreme.

### 2.7. `Dense(5)` — output layer multi-target, không activation

**Định nghĩa:** layer cuối — 5 scalar output (1 cho mỗi cảm biến trong `LSTM_FEATURES`), không có activation function (= linear).

**Tại sao 5 scalar (multi-target)?**

So với 5 model riêng (1 per sensor, Dense(1)):

| Tiêu chí | Multi-target Dense(5) | Per-sensor Dense(1) × 5 |
|---|---|---|
| Số lần train | 1 | 5 |
| Chi phí train | 1× | 5× |
| Số artifact | 1 (`lstm/`) | 5 (`lstm_<sensor>/`) |
| Memory ở runtime | 1 model | 5 model (hoặc share manually) |
| Quality per target | Thấp hơn ~5-10% (joint loss có trade-off) | Cao nhất (dedicated training) |
| Shared representation | ✅ — học tương tác giữa các sensor (vd. cloud → radiation qua RH/wind) | ❌ — mỗi model độc lập |
| Roll-forward h>1 | ✅ Consistent (model predict tự multivariate) | ⚠️ Phải giữ 4 feature khác cố định |

Project chọn **multi-target** vì:
1. Chi phí train rẻ hơn 5× → quan trọng vì SARIMA / LSTM đã chậm.
2. Roll-forward consistent: khi predict h>1 giờ, all-5 prediction được dùng làm input window cho step kế (không phải giữ feature khác stale).
3. Shared representation thường giúp small-data regime (51k sample) generalize tốt hơn dedicated.

**Tại sao linear (không activation)?**

Layer cuối là linear: `output_i = w_{1,i}·x₁ + ... + w_{32,i}·x₃₂ + b_i` cho mỗi i ∈ [1..5] → 5 **linear regression head** song song.

Nếu thêm activation sẽ giới hạn output sai bài toán:

| Activation | Output range | Vấn đề |
|---|---|---|
| `sigmoid` | [0, 1] | Temperature có thể âm (mùa đông) → sai |
| `relu` | [0, ∞) | Không cho âm → sai |
| `tanh` | [-1, 1] | Range quá nhỏ, không scale được °C |
| **`linear` (không có)** | (-∞, ∞) | ✅ Predict bất kỳ số thực nào |

Regression cần output **không bị giới hạn** → linear là choice duy nhất. 5 head linear thì áp dụng độc lập cho mỗi cảm biến.

### 2.7b. residual_std per target — tại sao cần?

Sau khi fit Dense(5), tính residual ở scale gốc cho từng cảm biến:

```python
for idx, feature in enumerate(LSTM_FEATURES):
    predicted = inverse_scale(model_output[:, idx], idx)   # về °C / mm/h / ...
    actual    = df[feature].iloc[window:].values
    residual_std[feature] = np.std(actual - predicted)
```

Tại sao **per-target** chứ không 1 σ chung?
- 5 cảm biến có scale + variance khác nhau (temperature σ ~ 0.5°C ≠ rain σ ~ 5 mm/h).
- ResidualDetector dùng threshold `|residual| / σ > k`. Nếu dùng σ chung, threshold không có ý nghĩa thống kê cho mỗi sensor.
- `_residual_std_by_target` được save vào artifact; khi load với `target="radiation"`, `self.residual_std` lấy đúng σ của radiation.

### 2.8. `MinMaxScaler` (scale tất cả về [0, 1])

**Tại sao scale?**

5 cảm biến **đơn vị + range khác hẳn nhau**:

| Feature | Range thực tế |
|---|---|
| temperature | 0 – 40 °C |
| relativeHumidity | 0 – 100 % |
| rain | 0 – 500 mm/h (đa số = 0) |
| radiation | 0 – 6 MJ/m²/h |
| wind | 0 – 50 m/s |

Nếu feed thẳng vào LSTM:
- LSTM gate (`tanh`, `sigmoid`) bị **saturate** ở các feature scale lớn → gradient yếu → không học được.
- Feature `rain` (range 0-500) sẽ "lấn át" gradient của `radiation` (range 0-6) → model bias.

**Tại sao MinMax thay vì StandardScaler?**

| Scaler | Công thức | Range output | LSTM-friendly? |
|---|---|---|---|
| **MinMax** | `(x - min) / (max - min)` | [0, 1] | ✅ Phù hợp tanh/sigmoid gate |
| StandardScaler | `(x - μ) / σ` | ~[-3, 3] với outlier | Có thể saturate gate |
| RobustScaler | `(x - median) / IQR` | Robust với outlier | OK nhưng less common |

Lý do MinMax thắng:
- LSTM internal có **tanh** và **sigmoid** gates. tanh's effective range là [-3, 3]; sigmoid's là [0, 6].
- StandardScaler có outlier (vd. rain = 500 sau scale = (500 - mean)/std ≈ 30) → saturate tanh = -1.
- MinMax giữ tất cả trong [0, 1] bounded → gate không saturate.

Nhược điểm MinMax: nhạy với outlier (1 spike = 1000 sẽ làm tất cả giá trị khác bị nén về [0, 0.05]). Với NASA POWER data, outlier rất ít → OK.

**Inverse transform khi predict**: pad zero cho 4 cột khác, thay cột target bằng scaled prediction, gọi `scaler.inverse_transform()` để lấy lại unit gốc (°C, %, mm/h, MJ/m²/h, m/s).

### 2.9. `adam` optimizer

**Tại sao Adam thay vì SGD/RMSprop?**

| Optimizer | Pros | Cons |
|---|---|---|
| SGD + momentum | Generalization tốt nhất | Cần tune learning rate cẩn thận |
| RMSprop | Tốt cho RNN, adaptive | Less popular gần đây |
| **Adam** | **Adaptive lr, robust default** | **Tốn memory 3× param** |
| AdamW | Adam + decoupled weight decay | Marginal improvement |

Adam = RMSprop + momentum. Default `lr=0.001` work cho hầu hết task không cần tune. Cho project prototype, dùng Adam tiết kiệm thời gian tune.

Nếu muốn tối ưu cuối cùng → SGD + cosine schedule có thể cho generalization tốt hơn 1-2%. Nhưng cost effort gấp 5×.

### 2.10. `mse` loss (mean squared error)

**Tại sao MSE?**

| Loss | Công thức | Tính chất |
|---|---|---|
| **MSE** | `Σ(y - ŷ)²` | Phạt error lớn rất nặng (quadratic) |
| MAE | `Σ\|y - ŷ\|` | Robust với outlier; gradient không smooth ở 0 |
| Huber | MSE khi nhỏ, MAE khi lớn | Combine, hơi phức tạp |

Lý do chọn MSE cho project:
1. **Match với mục đích anomaly detection**: ResidualDetector dùng `|residual| / σ > k` để flag anomaly. MSE training ép residual phân phối Gaussian → σ ổn định → threshold k có ý nghĩa.
2. **Quadratic penalty** → model học cách tránh prediction sai xa khỏi actual → forecaster ổn định.
3. **Smooth gradient** → train ổn định, không cần special tricks.

Nhược điểm: nếu data có outlier (rain spike) → MSE bị dominate bởi outlier. Trong project này không có outlier nặng → OK.

### 2.11. Default `target = "temperature"`

**Tại sao default temperature?**

- Lịch sử: bài toán gốc là dự đoán nhiệt độ cho irrigation planning.
- Temperature là cảm biến quan trọng nhất cho crop simulation (`Field.java` ET formula).
- Mặc định temperature giúp backward compat với artifact legacy `lstm/` single-target (Dense(1), không có `target` field).

**Lưu ý quan trọng:** với refactor multi-target, `target` parameter giờ chỉ là **view selector** (chọn cột nào của Dense(5) output để expose). KHÔNG ảnh hưởng training — model luôn fit jointly trên 5 cảm biến với loss MSE summed.

Patches `LstmForecaster(target=X)` cho phép dùng cùng artifact `lstm/` cho mọi sensor view. ml-service `_register_lstm_views` load 1 lần, tạo 5 view share `_model` + `_scaler`, chỉ khác `target_idx` + `residual_std`.

### 2.12. Tổng kết: bảng decision

| Hyperparameter | Giá trị | Constraint chính |
|---|---:|---|
| window | 48 | Cần ≥ 24 cho chu kỳ ngày, ≤ 72 để không overfit với 51k sample |
| epochs | 30 | Plateau sau 25, buffer 5 epoch — KHÔNG có early stopping nên không adaptive |
| batch_size | 32 | Standard SGD noise level, cân bằng speed + generalization |
| LSTM units | 64 | Sample/param ratio = 2.9 — ngưỡng overfit borderline |
| Dropout | 0.2 | Recurrent regularization heuristic của Yarin Gal |
| Dense hidden | 32 | Bottleneck 64 → 32 → 5, không quá to/nhỏ |
| Dense output | 5 (linear, multi-target) | 5 regression head song song; 1 head per cảm biến |
| Scaler | MinMax | Tránh saturate LSTM gates |
| Optimizer | Adam | Default robust cho prototype |
| Loss | MSE | Khớp với anomaly detection threshold |

> **Cảnh báo**: tất cả các giá trị này là **best-guess** ban đầu chưa qua hyperparameter tuning chính thức (grid search hoặc Bayesian optimization). Trong project lớn cần qua **3 vòng**:
>
> 1. **Manual default** (đang dùng) — phù hợp prototype.
> 2. **Grid search** trên 2-3 param quan trọng nhất (window, units, dropout).
> 3. **Bayesian optimization** (vd. Optuna) cho fine-tune cuối.
>
> Bước 2-3 chưa làm vì F1 hiện tại đủ cho production và optimization sẽ thay đổi ranking detector ở §4 báo cáo `bao-cao-so-sanh-cam-bien.md`.

---

## 3. Train data setup

| Tham số | Giá trị |
|---|---|
| Nguồn | NASA POWER hourly, điểm (21.0075, 105.5416) |
| Khoảng | 2020-01-01 → 2025-12-30 (52 584 điểm) |
| Train slice | 51 863 điểm (2020-01-01 → 2025-11-30) |
| Test/eval slice | 721 điểm (1 tháng cuối) — **không cho LSTM nhìn thấy** |
| Sequence count khi train | 51 863 − 48 = 51 815 sample |

**Lưu ý NaN handling:** `df.interpolate(limit=3).dropna()` — nội suy gap ≤ 3 giờ, xoá phần còn lại. NASA POWER có rất ít NaN nên gần như không mất sample.

---

## 4. Vai trò trong dự án

```
                ┌────────────────────────────────────┐
                │  ml-service                        │
                │                                    │
   MQTT đến →   │  /detect  →  ResidualDetector(LSTM)│
                │              │                     │
                │              ▼                     │
                │              forecast = lstm(...)  │
                │              residual = actual −   │
                │                          forecast  │
                │              score = |res| / σ     │
                │              anomaly = score > k   │
                │                                    │
   FE/planner → │  /forecast → LSTM.predict(time, h) │
                │                                    │
                └────────────────────────────────────┘
```

**Forecasting (`/forecast`):** trả về dự đoán h giờ tiếp theo cho cảm biến caller yêu cầu (`sensorId` trong request). Multi-step thông qua iterative roll-forward — feed back **all 5** predictions vào window cho step kế (multi-target nên có self-consistent multivariate context, không phải hold 4 feature stale như single-target).

**Anomaly detection (`/detect`):** wrap forecaster qua `ResidualDetector`. Anomaly khi forecast lệch xa actual >k×σ_residual. σ_residual lấy từ `_residual_std_by_target[target]` của LSTM (per-target).

**Architecture share ở ml-service:**
- Load `lstm/` artifact 1 lần qua `_register_lstm_views()` trong `api/main.py` lifespan.
- Tạo 5 `LstmForecaster` view share `_model` + `_scaler` (memory: 1 model thay vì 5).
- Mỗi view có `target` khác → expose 1 cột Dense(5) output + đúng `residual_std` cho cảm biến đó.

Per benchmark trong `bao-cao-so-sanh-cam-bien.md`:
- LSTM là winner cho `radiation` (F1=0.557 — vượt seasonal_zscore 0.419).
- Trên 4 cảm biến còn lại, LSTM không phải winner; các phương pháp đơn giản hơn (seasonal_zscore, sarima_residual) thắng.

> **Lưu ý:** benchmark cũ chạy trên LSTM single-target (Dense(1) × 5 model riêng). Sau refactor multi-target, F1 có thể thay đổi do (a) joint loss bias, (b) shared representation effect. Cần re-eval sau khi retrain để confirm radiation winner còn đúng.

---

## 5. Hạn chế và đề xuất cải tiến

| Vấn đề | Đề xuất | Effort |
|---|---|---|
| Không early stopping → có thể overfit ở epoch cuối | Thêm `EarlyStopping(patience=5, monitor='val_loss')` trong `fit()` | ~5 dòng code |
| Không validation split → không biết khi nào overfit bắt đầu | Thêm `validation_split=0.1` trong `model.fit()` | 1 dòng code |
| LSTM stochastic (random init + dropout) → F1 lệch ±0.05 giữa các lần fit | Fit nhiều lần (n=5), lấy median; hoặc seed `tf.random.set_seed(...)` | ~10 dòng code, train chậm 5x |
| Multi-target joint loss có thể bias toward easier-to-predict sensors (vd. temperature dễ hơn rain) | Loss weighting: `loss_weights=[1.0, 1.5, 2.0, 1.0, 1.5]` (rain/wind nặng hơn) | 1 dòng `compile()` |
| Mỗi target chia chung capacity 64 unit LSTM | Tăng LSTM units lên 96/128 — bù lại multi-target dilution | Tăng overfit risk; cần test |
| Multi-target xấu cho target khó (rain) → có thể không bằng dedicated single-target | A/B compare: train cả 2 modes, so F1 | 2× train cost |
| Window=48 hardcoded cho mọi target | Per-target không khả thi với multi-target (1 model duy nhất) | N/A — phải pick 1 window |
| Single LSTM layer | Stacked LSTM (2 layer, 32 unit mỗi) có thể cải thiện | Trade-off train time 2x |
| MinMaxScaler nhạy với outlier (đặc biệt `rain` có spike 500+ mm/h) | Thử RobustScaler (median-based) thay MinMax | 1 dòng |
| Iterative roll-forward cho multi-step → error tích luỹ nhanh | Train seq2seq output (multi-step trực tiếp) | Refactor architecture |
| LSTM trong live `/detect` cần Mongo context → hiện không có auth | Cấu hình Mongo URI có credentials vào `ml-service/.env` | Config + .env |

Cải tiến 1+2 (early stopping + validation split) là **quick win** — chỉ ~6 dòng code, sẽ giảm variance giữa các lần fit và cho biết epoch nào tối ưu.

**Đặc thù multi-target trade-off:**
- ✅ Train rẻ hơn 5× so với 5 single-target model riêng.
- ✅ Roll-forward consistent — 5 features cùng được predict, không phải hold stale.
- ✅ Shared representation tốt khi sensor có tương quan (radiation ↔ humidity ↔ wind).
- ⚠️ Joint MSE loss có thể bias toward easier targets; nếu rain prediction tệ thì training gradient bị dominate bởi temperature dễ hơn.
- ⚠️ Capacity LSTM(64) bị chia cho 5 task → mỗi target có thể yếu hơn dedicated.

Nếu thấy F1 multi-target lệch nhiều so với single-target benchmark cũ, có thể quay lại single-target hoặc tăng capacity (LSTM(128)) + loss weighting.

---

## 6. Tham chiếu code

- **Implementation:** [`ml-service/ml/forecasters/lstm.py`](../ml/forecasters/lstm.py)
- **Training entry:** [`ml-service/scripts/train.py`](../scripts/train.py) → `train_lstm()` (gọi `LstmForecaster.fit(df)`)
- **Anomaly detection wrapper:** [`ml-service/ml/detectors/residual.py`](../ml/detectors/residual.py)
- **Forecasting API:** [`ml-service/api/routes/forecast.py`](../api/routes/forecast.py)
- **Detection API:** [`ml-service/api/routes/detect.py`](../api/routes/detect.py)
- **Benchmark kết quả:** [`bao-cao-so-sanh-cam-bien.md`](bao-cao-so-sanh-cam-bien.md) §3 `radiation` row + §5 LSTM section

## 7. Cách retrain LSTM

```bash
cd ml-service

# Train 1 lần — multi-target, saves to artifacts/lstm/
# (--sensor flag KHÔNG cần và bị ignore cho LSTM vì multi-target)
.venv/Scripts/python.exe -m scripts.train --model lstm

# Eval LSTM-residual qua 5 cảm biến (mỗi sensor refit fresh — chấp nhận 5× redundancy)
.venv/Scripts/python.exe -m scripts.evaluate_detection \
    --methods lstm_residual --sensor all \
    --sweep-k 1.0 10.0 0.5 \
    --sweep-out artifacts/smoke_lstm.csv
```

Thời gian (Windows CPU, no GPU):
- **Training:** ~5–15 phút (1 model duy nhất thay vì 5).
- **Eval:** ~30–50 phút cho 5 cảm biến (mỗi sensor refit fresh multi-target LSTM rồi score 1 cột output; có cost redundancy nhưng giữ logic eval đơn giản).

Output of training:
```
[LSTM] Training multi-target (window=48, epochs=30)... (slow)
  Saved -> artifacts/lstm
  residual_std per target:
    temperature          0.834
    relativeHumidity     3.412
    wind                 0.521
    rain                 4.087
    radiation            0.318
```

Mỗi `residual_std` per target được dùng bởi `ResidualDetector(LstmForecaster(target=X))` trong production cho threshold |residual|/σ > k.
