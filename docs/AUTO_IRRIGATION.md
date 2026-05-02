# Tài liệu kỹ thuật — Hệ thống tưới tự động qua MQTT

> Tài liệu này mô tả thiết kế và vận hành chức năng tưới tiêu trong dự án
> CASSAVA_IOT, bao gồm cả phần điều khiển van bơm thực tế (OPERATION) lẫn
> chế độ mô phỏng (SIMULATION). Các quyết định kiến trúc — đặc biệt là
> việc dùng **MQTT bridge** giữa pi3 và máy chủ prod thay vì kết nối
> thẳng — được giải thích kèm trade-off.

---

## 1. Tổng quan

Hệ thống tưới tiêu có hai loại điều khiển và hai chế độ thực thi, vận hành
độc lập nhau:

| Trục | Lựa chọn | Ý nghĩa |
|---|---|---|
| **Loại điều khiển** | `autoIrrigation = true` | Mô hình mô phỏng tự sinh lệnh tưới khi đất khô |
| | `autoIrrigation = false` | Người dùng tạo lịch tưới tay qua FE |
| **Chế độ thực thi** | `mode = SIMULATION` | Chỉ mô phỏng, **không** gửi lệnh ra thiết bị |
| | `mode = OPERATION` | Publish MQTT command tới edge để mở van bơm thật |

Hai trục này độc lập: một field có thể là `OPERATION + autoIrrigation=false`
(người dùng đặt lịch tưới tay, hệ thống bơm thật) hoặc `SIMULATION + autoIrrigation=true`
(chạy mô phỏng để xem dự đoán mà không động vào thiết bị).

`FieldMongoService` validate `mode ∈ {SIMULATION, OPERATION}` khi tạo/sửa
field. `IrrigationScheduleService.create()` từ chối tạo lịch tay nếu
`autoIrrigation=true` (hai cơ chế loại trừ lẫn nhau cho cùng một field).

---

## 2. Vòng đời một lịch tưới (manual schedule)

Lịch tưới (`IrrigationSchedule`) đi qua các trạng thái sau trong MongoDB:

```
PENDING ──┬──▶ SENT ──┬──▶ DONE       (edge ack thành công)
          │           ├──▶ FAILED     (edge ack lỗi)
          │           └──▶ NO_ACK     (quá timeout, BE không nhận ack)
          └──▶ RUNNING ──▶ DONE       (chỉ trong SIMULATION mode)
          └──▶ CANCELLED              (người dùng huỷ trước khi gửi)
```

`IrrigationScheduleScheduler` chạy nền, tick 15s/30s, drive lifecycle:

- **OPERATION mode**: tới `scheduledTime` → publish `OperationCommand` lên
  topic `cassava/field/{fieldId}/valve/{valveId}/cmd` (QoS 1) → đánh dấu
  `SENT`. Một tick khác kiểm tra timeout (`mqtt.operation.ack-timeout-seconds`,
  mặc định 60s) → nếu chưa có ack thì set `NO_ACK`.
- **SIMULATION mode**: bypass MQTT hoàn toàn. Tick 1: `PENDING → RUNNING`
  (set `startedAt`). Tick 2: nếu `startedAt + durationSeconds` đã trôi
  qua thì `RUNNING → DONE`.

Cùng một controller, cùng một repo, cùng một bảng FE — chỉ khác phần
thực thi nằm trong scheduler.

`MqttAckListener` subscribe `cassava/field/+/valve/+/ack`, parse JSON
`OperationAck`, dispatch vào `IrrigationScheduleService.handleAck()` để
chuyển `SENT → DONE/FAILED`.

---

## 3. Topology mạng

```
                  ┌─────────────────────────────────────────┐
                  │ Server prod (UET, 112.137.129.218)      │
                  │                                          │
                  │  ┌───────────────┐    ┌───────────────┐ │
                  │  │ Spring Boot   │◀──▶│  Mosquitto    │ │
                  │  │ (cassava-be)  │    │  127.0.0.1    │ │
                  │  │  port 8081    │    │  port 1883    │ │
                  │  └───────────────┘    └───────┬───────┘ │
                  └─────────────────────────────────│───────┘
                                                    │ MQTT bridge
                                                    │ (TCP outbound
                                                    │  từ pi3)
                  ┌─────────────────────────────────│───────┐
                  │ pi3 (edge, sau NAT)             ▼       │
                  │                          ┌───────────┐  │
                  │   ESP8266/sensor ───────▶│ Mosquitto │  │
                  │                          │ localhost │  │
                  │                          │ port 1883 │  │
                  │                          └─────┬─────┘  │
                  │                                │        │
                  │   ┌─────────────────┐  ┌───────┴──────┐ │
                  │   │ edge_to_mongo_* │  │ dk_bom_mqtt  │ │
                  │   │ (persistence)   │  │ (pump ctrl)  │ │
                  │   └────────┬────────┘  └──────┬───────┘ │
                  │            │                  │ GPIO    │
                  │            │ libmongoc        │ relay   │
                  │            ▼                  ▼         │
                  │      MongoDB ◀───────  Pump1..Pump4     │
                  │     (prod, port                          │
                  │      27017)                              │
                  └──────────────────────────────────────────┘
```

Lưu ý: **persistence** (ghi sensor vào MongoDB) đi thẳng qua libmongoc,
không qua bridge. Bridge chỉ phục vụ:
1. Lệnh tưới prod → pi3 (`cmd in`)
2. Ack pi3 → prod (`ack out`)
3. Sensor data pi3 → prod để BE chạy anomaly check (`weatherStation2 out`,
   `field1..field4 out`)

---

## 4. Tại sao phải dùng bridge thay vì kết nối thẳng

Đây là quyết định kiến trúc quan trọng. Có 3 phương án:

### Phương án A — BE kết nối thẳng tới mosquitto pi3

```
BE (prod) ─── kết nối tới ───▶ pi3:1883 (qua Internet)
```

**Vấn đề**:
- Pi3 nằm sau NAT (router gia đình / mạng UET nội bộ). Không có IP
  công cộng cố định. Để BE reach được, phải port-forward `1883/tcp`
  ngoài Internet.
- Lộ port MQTT 1883 ra Internet → rủi ro bảo mật (1883 là plain TCP,
  ai biết IP đều có thể publish/subscribe nếu không có TLS).
- Khi pi3 mất IP công cộng (DHCP lease, đổi nhà mạng) → BE phải
  reconfigure.
- Không có cơ chế retry chuẩn nếu pi3 down nhiều ngày — message QoS 1
  của BE sẽ tích tụ trong queue local, dễ tràn.

### Phương án B — Edge kết nối thẳng tới mosquitto prod

```
edge (pi3) ─── kết nối tới ───▶ prod:1883 (qua Internet)
```

Tức là pi3 không chạy mosquitto local; tất cả publisher/subscriber trên pi3
(ESP, `dk_bom_mqtt`, `edge_to_mongo_*`) đều dial thẳng tới
`tcp://112.137.129.218:1883`.

**Vấn đề**:
- ESP8266 / cảm biến: firmware cũ đã hard-code `localhost:1883`. Đổi
  hardcode đồng nghĩa flash lại tất cả ESP — không thực tế.
- Nếu Internet pi3 đứt vài phút, sensor publish bị mất hoàn toàn (không
  có buffer local).
- Mỗi edge program tự handle reconnect riêng, code phức tạp.
- Mọi traffic sensor đi qua Internet → tốn băng thông & tăng độ trễ.

### Phương án C — MQTT bridge (lựa chọn của dự án)

```
edge ──▶ mosquitto pi3 ──▶ bridge (outbound) ──▶ mosquitto prod ──▶ BE
                ◀────────                ◀──────────
```

Hai broker mosquitto, một bridge nối hai broker. Pi3 chủ động dial ra prod
(outbound TCP).

**Ưu điểm**:
- **NAT-friendly**: pi3 chỉ cần Internet outbound, không cần port-forward.
- **Edge programs không thay đổi**: mọi publisher/subscriber trên pi3
  vẫn nói chuyện với `localhost:1883` như cũ. Khi flash thêm ESP mới,
  không phải biết IP prod.
- **Buffer local**: nếu Internet đứt, sensor publish vẫn vào mosquitto
  pi3, edge_to_mongo_* vẫn tiếp tục chạy (ghi Mongo chỉ phụ thuộc
  Internet, sensor flow nội bộ vẫn hoạt động). Khi Internet phục hồi,
  bridge tự reconnect và đẩy backlog (với QoS 1).
- **Tách biệt chu kỳ vận hành**: BE và edge có thể restart độc lập,
  bridge tự retry.
- **Bảo mật tốt hơn**: chỉ 1 phía (pi3) cần outbound; có thể giới hạn
  firewall prod chỉ chấp nhận IP pi3 nếu cần.
- **Standard pattern**: là cách Eclipse Mosquitto khuyến nghị cho
  topology hub-and-spoke.

**Nhược điểm**:
- Thêm 1 layer (mosquitto pi3) → một điểm fail nữa, cấu hình thêm.
- Trễ thêm vài chục ms (so với direct).
- Cần quản lý persistent session để không mất ack QoS 1 khi reconnect.

→ Trade-off chấp nhận được vì lợi ích vận hành lớn hơn nhiều chi phí.

---

## 5. Cấu hình bridge

File `deploy/mosquitto/cassava-bridge.conf` được copy vào
`/etc/mosquitto/conf.d/` trên pi3:

```
connection cassava-prod
address 112.137.129.218:1883

remote_username libe
remote_password 123456
remote_clientid pi3-bridge
local_clientid  cassava-prod-bridge

cleansession false       # giữ session để QoS 1 không mất
try_private false
notifications true
start_type automatic
keepalive_interval 60

# BE -> edge
topic cassava/field/+/valve/+/cmd in 1
# edge -> BE
topic cassava/field/+/valve/+/ack out 1

# Sensor (edge publish, BE subscribe để chạy anomaly check)
topic /sensor/weatherStation2 out 1
topic field1                  out 1
topic field2                  out 1
topic field3                  out 1
topic field4                  out 1
```

Hai chiều `in` / `out` rõ ràng — không bị loop topic. Bridge tự handle
reconnect (`start_type automatic`).

Pi3 mosquitto cấu hình `allow_anonymous true` (chỉ phục vụ localhost), prod
mosquitto bật `password_file` với user `libe / 123456`. Bridge dùng
`remote_username/password` để authenticate ngược lên prod.

---

## 6. Schema giao thức

### 6.1 Operation Command (BE → edge)

Topic: `cassava/field/{fieldId}/valve/{valveId}/cmd`, QoS 1, JSON:

```json
{
  "scheduleId": "65f1abcdef12345...",
  "action": "OPEN_TIMED",
  "durationSeconds": 30,
  "issuedAt": 1737982742000
}
```

- `scheduleId` — id của `IrrigationSchedule`, dùng để khớp ack về đúng record.
- `action` — hiện chỉ hỗ trợ `OPEN_TIMED` (mở van trong N giây rồi tự đóng).
- `durationSeconds` — bắt buộc, edge từ chối nếu > `MAX_DURATION_SEC` (3600).
- `issuedAt` — epoch ms, dùng để debug.

### 6.2 Operation Ack (edge → BE)

Topic: `cassava/field/{fieldId}/valve/{valveId}/ack`, QoS 1, JSON:

```json
{
  "scheduleId": "65f1abcdef12345...",
  "ack": "DONE",
  "ackAt": 1737982772000,
  "errorMessage": null
}
```

- `ack ∈ {DONE, FAILED}`.
- `errorMessage` chỉ điền khi FAILED.

### 6.3 Relay topic (legacy)

`dk_bom_mqtt` chuyển đổi từ JSON command sang relay topic cũ
(`Pump<valveId>`) để giữ tương thích firmware ESP đang triển khai:

```
mosquitto_pub -t Pump1 -m "1"   # mở
mosquitto_pub -t Pump1 -m "0"   # đóng
```

Bảng `RELAYS[]` trong `dk_bom_mqtt.c` map `valveId → relay topic`. Khi
thêm van mới chỉ cần thêm hàng và recompile.

---

## 7. Cơ chế ack timeout (NO_ACK)

Một schedule sau khi `SENT` mà BE không nhận ack trong
`mqtt.operation.ack-timeout-seconds` (60s mặc định) thì được set
`NO_ACK`. Lý do:
- Edge crash giữa lúc đang mở van.
- Internet pi3 đứt sau khi nhận cmd, không gửi ack về được.
- ESP relay không hồi đáp.

`NO_ACK` không tự động retry — admin phải kiểm tra hiện trạng physical
(van có thật sự mở không) trước khi tạo schedule mới. Đây là quyết định
an toàn để tránh tưới chồng chéo nếu edge thực ra đã mở van rồi.

---

## 8. Pipeline cảm biến và anomaly detection

Hệ thống còn trục thứ hai dùng chung broker MQTT: **đường sensor**.

```
ESP/sensor ──▶ mosquitto pi3 ──┬──▶ edge_to_mongo_* ──▶ MongoDB sensor_value
                               │
                               └──▶ bridge ──▶ mosquitto prod ──▶ BE.MqttSensorListener
                                                                       │
                                                                       ▼
                                                              RangeCheckService
                                                              (Tier 1 anomaly)
```

- **Edge owns persistence**: hai chương trình C standalone
  (`edge_to_mongo_weather.c`, `edge_to_mongo_soil.c`) chạy trên pi3,
  subscribe local broker, parse payload `key value;...`, ghi thẳng vào
  MongoDB qua libmongoc. BE không tham gia bước ghi.
- **BE owns validation**: `MqttSensorListener` subscribe cùng các topic,
  chạy `RangeCheckService.check(sensorId, value)` với threshold cấu
  hình ở `anomaly.range.<sensorId>.{min,max}`. Out-of-range → log
  `WARN [sensor] RANGE_FAIL`.

Range Check (Tier 1) là tier rẻ nhất trong tài liệu `Anomaly_detection.docx`.
Các tier 2-4 (Z-score, Seasonal Z-score, ML imputation) chưa triển khai —
sẽ thêm trên cơ sở `RangeCheckService` ở giai đoạn sau.

### Đơn vị radiation

Cảm biến bức xạ trả `MJ/m²/h`. Mô hình mô phỏng (`entity/Field.java`)
trước đây giả định W/m² nên có công thức convert. Sau khi đồng bộ:
- `Rs = radiation` (giữ nguyên đơn vị MJ/m²/h)
- `ppfd = radiation × 597.22` (1 MJ/m²/h ≈ 277.78 W/m², × 2.15 → PPFD μmol/m²/s)
- Range Check: `radiation.max = 6 MJ/m²/h` (≈ 1500 W/m² nắng cực đại nhiệt đới)

---

## 9. Module thành phần

### Backend (Spring Boot)
| Module | Vai trò |
|---|---|
| `mqtt/MqttConfig` | Tạo Paho client connect tới mosquitto prod (loopback) |
| `mqtt/MqttCommandPublisher` | Publish `OperationCommand` JSON, QoS 1 |
| `mqtt/MqttAckListener` | Subscribe `…/ack`, dispatch vào schedule service |
| `mqtt/MqttSensorListener` | Subscribe weather + soil, gọi `RangeCheckService` |
| `service/Mongo/IrrigationScheduleService` | CRUD + xử lý ack (DONE/FAILED) |
| `service/Mongo/IrrigationScheduleScheduler` | Tick 15s/30s, drive lifecycle |
| `service/anomaly/RangeCheckService` | Tier 1 validation, threshold từ config |

### Edge (C standalone)
| Binary | File | Vai trò |
|---|---|---|
| `edge_to_mongo_weather` | `edge/edge_to_mongo_weather.c` | Subscribe `/sensor/weatherStation2`, ghi Mongo |
| `edge_to_mongo_soil` | `edge/edge_to_mongo_soil.c` | Subscribe `field1..field4`, ghi Mongo |
| `dk_bom_mqtt` | `edge/dk_bom_mqtt.c` | Subscribe cmd, drive Pump relay, publish ack |

Mỗi `.c` file là self-contained — broker URL, credentials, valve mapping
hardcode trong block CONFIG ở đầu file. Compile bằng `cc` với
`libpaho-mqtt3c`, `libmongoc-1.0`, `libpthread`. Đơn giản, dễ debug, dễ
chạy như systemd unit trên Raspberry Pi.

### Frontend (React + Vite)
| Module | Vai trò |
|---|---|
| `pages/Fields/FieldDetail/ManualIrrigationTab` | Tạo/huỷ lịch tưới tay |
| `pages/Fields/FieldDetail/IrrigationTab` | Biểu đồ độ ẩm đất + RangePicker |
| `pages/Fields/FieldDetail/HistoryTab` | Lịch sử tưới đã chạy |
| `pages/Weather/*` | Dashboard và detail cảm biến thời tiết theo nhóm |
| `services/api.js` | Axios + JWT interceptor + 401 redirect |
| `components/Layout/MainLayout` | Auth guard, redirect /login nếu thiếu session |

---

## 10. Bảo mật

- **Auth MQTT**: prod bật `allow_anonymous false`, password trong
  `/etc/mosquitto/passwd` (libe / 123456). Pi3 anon localhost là an toàn
  vì chỉ phục vụ process trên cùng máy.
- **JWT**: BE issue JWT 24h, FE lưu trong `localStorage`. Interceptor
  gắn `Authorization: Bearer` vào mỗi request.
- **401 handling**: nếu BE trả 401, FE clear localStorage + redirect
  `/login` (tránh tình trạng giữ admin badge mà thao tác fail).
- **Rủi ro hiện tại**: password 1883 truyền plain text qua Internet
  (UET → pi3). Phương án nâng cấp: TLS port 8883, hoặc VPN nội bộ.
  Không blocking cho demo nhưng sẽ cần xử lý trước khi triển khai
  production thực sự.

---

## 11. Hướng phát triển

1. **TLS cho bridge** (`mqtts://` port 8883) — bảo vệ credential khỏi
   sniff trên Internet.
2. **Anomaly tiers 2-4** — Z-score / Seasonal Z-score / ML imputation
   theo `Anomaly_detection.docx`.
3. **Smart auto-irrigation** — kết hợp Range Check + dự báo thời tiết
   để quyết định tưới sớm/muộn (hiện tại chỉ trigger theo độ ẩm hiện tại).
4. **Failure recovery cho NO_ACK** — UI rõ hơn để admin biết schedule
   nào cần kiểm tra physical, có nút "Mark as completed" sau khi xác nhận.
5. **Multi-edge** — hệ thống đang giả định 1 pi3. Nếu mở rộng sang
   nhiều site, mỗi pi3 tự bridge về prod, dùng `field.groupId` để định tuyến.

---

## Phụ lục — file liên quan

- `deploy/MQTT.md` — runbook vận hành MQTT, payload schema chi tiết
- `deploy/mosquitto/cassava-bridge.conf` — bridge config mẫu
- `edge/README.md` — build & run edge programs
- `deploy/DEPLOY.md` — flow build/upload/restart cho prod
- `CLAUDE.md` — kiến trúc tổng thể repo
