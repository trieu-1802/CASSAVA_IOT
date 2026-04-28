# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CASSAVA_IOT is a full-stack Smart Farming IoT platform for managing cassava crop fields with real-time sensor monitoring and automated irrigation control. It consists of a React frontend and a Spring Boot backend connected to MongoDB.

## Build & Run Commands

### Frontend (`CassavaFE/`)
```bash
cd CassavaFE
npm install           # install dependencies
npm run dev           # dev server at http://localhost:5173
npm run build         # production build to dist/
npm run lint          # ESLint
npm run preview       # preview production build
```

### Backend (`cassavaBE/`)
```bash
cd cassavaBE
mvn clean install     # build + download dependencies
mvn spring-boot:run   # run at http://localhost:8081
mvn test              # run all tests
mvn test -Dtest=ClassName#methodName   # run a single test
```

Both FE and BE must run concurrently for development. FE Axios instances read `VITE_API_BASE` from env files — `.env.development` sets it to `http://localhost:8081`, `.env.production` sets it to `/cassava/api` (relative, resolved by nginx under the prod deploy). No Vite proxy config.

## Architecture

```
React SPA (5173) ──Axios──▶ Spring Boot API (8081) ──▶ MongoDB (remote)
                                    │                       ▲
                                    │                       │ libmongoc insert
                                    │               edge C binaries (pi3)
                                    │                       ▲
                  ┌─────────────────┴───────┐               │ subscribe MQTT
                  │                         │               │
              MQTT operation        MQTT sensor (anomaly)   │
              (private mosq.)       (private mosq.)         │
                  │                         │               │
              valves out / ack in    weather + soil readings
                  │                         │               │
                  └──────────────► edge (pi3) ◄─────────────┘
```

Two distinct MQTT subsystems on the same private mosquitto broker:
- **operation** — BE ↔ edge for irrigation commands/acks (`cassava/field/+/valve/+/{cmd,ack}`).
- **sensor** — edge publishes weather + soil readings on `/sensor/weatherStation2` and `field1..field4`. Persistence is owned by the **edge C binaries** (single-file `edge/edge_to_mongo_weather.c`, `edge/edge_to_mongo_soil.c`, compiled directly with `cc` on pi3) which insert directly into MongoDB; the BE listens to the same topics for **anomaly detection** (`MqttSensorListener` + `RangeCheckService`) but does not persist.

See `deploy/MQTT.md` for both architectures (operation + sensor data path).

Main app (`Demo1Application`) uses `@EnableCaching` and `@EnableScheduling`. `FieldSimulator.runScheduledSimulationForAllFields()` runs the crop simulation for every Mongo field twice daily at **07:00 and 17:00 `Asia/Ho_Chi_Minh`** (cron `0 0 7,17 * * *`). On-demand runs are still available via `GET /simulation/run?fieldId=X`.

### Backend (`cassavaBE/src/main/java/com/example/demo/`)

- **controller/**: REST endpoints. `UserController` lives at the root; the rest are under `controller/mongo/`:
  - `FieldMongoController` (`/mongo/field`) — MongoDB field CRUD, plus `POST /mongo/field/clone/{id}` (deep-copy) and `POST /mongo/field/resetCrop/{id}` (new growing cycle)
  - `FieldGroupController` (`/mongo/field-group`) — CRUD for field groups (groups share a weather station; every field must belong to one)
  - `FieldGroupSensorController` — sensor-to-group mapping (weather station sensors shared across a group's fields)
  - `FieldSensorController` (`/mongo/field/{fieldId}/sensor`) — per-field sensor mapping (soil moisture sensors)
  - `SensorValueController` (`/sensor-values`) — sensor history and combined values
  - `IrrigationHistoryController` (`/mongo/irrigation-history`) — irrigation record log
  - `IrrigationScheduleController` (`/mongo/irrigation-schedule`) — manual irrigation scheduling (`PENDING/SENT/RUNNING/DONE/CANCELLED/FAILED/NO_ACK` lifecycle — see "Manual irrigation execution" below)
  - `SimulationController` (`/simulation`) — run/chart simulation
  - `UserController` (`/api/auth`) — login, register, list users
- **service/**: Split between the root and a `Mongo/` subpackage.
  - `service/`: `JwtService`, `UserService`
  - `service/Mongo/`: `FieldMongoService`, `FieldGroupService`, `FieldGroupSensorService`, `FieldSensorService`, `FieldSimulator` (crop simulation + auto irrigation history), `SensorValueService`, `IrrigationHistoryService`, `IrrigationScheduleService`, `IrrigationScheduleScheduler` (15s/30s ticks driving the schedule lifecycle for both modes)
  - `service/anomaly/`: `RangeCheckService` (Tier-1 physical-range validation, thresholds via `anomaly.range.*` properties) + `RangeCheckResult`. Tiers 2–4 (Z-score, Seasonal Z-score, ML imputation per `Anomaly_detection.docx`) are not yet implemented.
- **mqtt/**: Both MQTT subsystems share a single Paho client bean (`MqttConfig.operationMqttClient()`) connected to the private mosquitto broker. See `deploy/MQTT.md`.
  - **Operation**: `MqttCommandPublisher` (publishes `OperationCommand` to `cassava/field/{fieldId}/valve/{valveId}/cmd` at QoS 1), `MqttAckListener` (subscribes to `cassava/field/+/valve/+/ack`, dispatches into `IrrigationScheduleService.handleAck`), `MqttTopics` (topic constants), `OperationCommand` / `OperationAck` (JSON POJOs).
  - **Sensor (anomaly)**: `MqttSensorListener` subscribes to `mqtt.sensor.weather-topic` + each `mqtt.sensor.soil-topics`, parses the `key value;` payload, runs each reading through `RangeCheckService`, and logs `[sensor] OK` or `[sensor] RANGE_FAIL`. **Does not persist** — the edge C binaries own `sensor_value` writes. `MqttSensorTopics` holds the `t/h/rad/rai/w → temperature/...` key map.
- **entity/**: Two separate `Field` classes (see disambiguation below), plus `User`, `FieldSensor`, `FieldGroup`, `FieldGroupSensor`, `SensorValue`, `FieldSimulationResult`, `IrrigationHistory`, `IrrigationSchedule`. A few simulation-only DTOs still live at the top level — `HistoryIrrigation` (used by `FieldSimulator` to convert in-memory irrigation events into `IrrigationHistory` Mongo docs), `MeasuredData` and `WeatherRequest` (referenced inside `entity/Field.java` only — both are in dead branches and could be removed with a small refactor)
- **repositories/**: Spring Data MongoDB repos under `repositories/mongo/` — includes `FieldGroupRepository`, `FieldGroupSensorRepository`, `FieldSimulationResultRepository`, `IrrigationHistoryRepository`, `IrrigationScheduleRepository`

**Field ↔ Group constraint**: `FieldMongoService.create()` rejects a field unless `groupId` references an existing `field_group`. Every field belongs to exactly one group; groups are the unit of weather-station sharing.

**Cascade delete**: `FieldMongoService.delete()` clears `field_sensor`, `sensor_value`, `simulation_result`, `irrigation_history`, and `irrigation_schedule` rows tied to the field before removing the field document. Any new field-scoped collection must be added here to avoid orphaned data.

**Reset crop**: `POST /mongo/field/resetCrop/{id}` resets per-crop state on the `Field` Mongo document (startTime, DAP=1, irrigating=false) for a new growing cycle. Note: per-crop history (`simulation_result`, `irrigation_history`) is **retained** and distinguished by `cropStartTime` — it is not cleared on reset.

**Auto vs. manual irrigation**: A field's `autoIrrigation` flag is mutually exclusive with manual scheduling. `IrrigationScheduleService.create()` rejects new schedules while `autoIrrigation=true`, and requires a valid `valveId` in the range 1–4 (either on the schedule or inherited from the field).

**Field mode (SIMULATION vs OPERATION)**: Every Mongo `Field` has a `mode` string (default `SIMULATION`, normalized + validated to one of `SIMULATION` / `OPERATION` in `FieldMongoService`). It is **independent** of `autoIrrigation` and only governs how a manual schedule is *executed*:

- `OPERATION` — the real path. `IrrigationScheduleScheduler` publishes the schedule to the operation MQTT broker (`MqttCommandPublisher`) at its `scheduledTime`, marks `SENT`, and waits for an ack on `cassava/field/+/valve/+/ack`. `MqttAckListener` resolves it to `DONE`/`FAILED`. If no ack arrives within `mqtt.operation.ack-timeout-seconds`, a separate tick marks it `NO_ACK`.
- `SIMULATION` — the demo/dev path. **No MQTT traffic**. The same scheduler tick promotes `PENDING → RUNNING` (sets `startedAt`); a second tick (`completeRunningSimulations`) promotes `RUNNING → DONE` once `startedAt + durationSeconds` has elapsed. The FE shows the same control screens; only the underlying execution differs.

Both modes share the same controller, repo, and FE tab — the divergence is contained inside `IrrigationScheduleScheduler`. Full architecture, payload schemas, and edge subscriber spec live in `deploy/MQTT.md`.
- **Jwt/**: `JwtUtils` (token gen/validation), `JwtAuthFilter` (request filter)
- **config/**: `SecurityConfig` (CORS + auth rules), `WebConfig`
- **firebase/**: legacy directory name — only `CorsConfig` remains (see CORS note below); the Firebase integration has been removed

Auth flow: JWT with 24h expiry (HS512). Roles are ADMIN and USER. Public endpoints: `/api/auth/**` only; `/mongo/**` and `/simulation/**` are permitAll. Token injected via `JwtAuthFilter` in the Spring Security filter chain.

**CORS gotcha**: Two separate configurations exist — `SecurityConfig` restricts CORS to `http://localhost:5173` for Spring Security's filter, and `firebase/CorsConfig.java` registers a `WebMvcConfigurer` bean that applies to `/**` with origins `http://localhost:5173` + `http://112.137.129.218` (the prod IP). In production the FE is served same-origin via nginx so CORS is not actually triggered; the configs matter only for dev and for any direct external caller.

### Frontend (`CassavaFE/src/`)

React 19 + Vite, Ant Design v6, React Router v7, Recharts for charts.

- **pages/**: Grouped by feature —
  - `Auth/` (Login, Register)
  - `Fields/`: `FieldList`, `FieldSoilSensors` (per-field soil moisture), `FieldDetail/` (`index.jsx` + tabs: `DiseaseTab`, `IrrigationTab`, `ManualIrrigationTab`, `YieldTab`, `HistoryTab`), plus feature-local `components/FieldModal`, `components/SimulationDashboard`
  - `FieldGroups/`: `FieldGroupList` + `components/FieldGroupModal` — CRUD for groups that share a weather station
  - `Weather/`: `WeatherGroupList` (group picker), `WeatherDashboard` (per-group `/weather/:groupId`), `WeatherDetail` (per-sensor `/weather/detail/:sensorId`) — weather is accessed via the group, not an individual field
  - `Users/` (UserList)
- **services/**: Four Axios instances, each with `baseURL` built from `import.meta.env.VITE_API_BASE` (fallback `http://localhost:8081`):
  - `api.js` → `${VITE_API_BASE}` — injects `Authorization: Bearer <token>` from `localStorage.user.accessToken`
  - `authService.js` → `${VITE_API_BASE}/api` — same `Authorization: Bearer` interceptor
  - `fieldService.js` → `${VITE_API_BASE}/mongo` — JWT interceptor attached; endpoints are `permitAll` in `SecurityConfig` but the token is still sent
  - `groupService.js` → `${VITE_API_BASE}/mongo/field-group`
- **components/**: `Layout/MainLayout` only (responsive sidebar — `Drawer` on mobile <768px, collapsible `Sider` on desktop). Feature-specific components live under their `pages/<feature>/components/` folder.
- Routing is defined inline in `App.jsx` (e.g. `/fields`, `/fields/:id`, `/fields/:fieldId/soil-sensor`, `/field-groups`, `/weather`, `/weather/:groupId`) — no separate route guards.

Auth state is managed entirely via `localStorage` (key: `user` with `accessToken` and `isAdmin` fields). No Redux/Zustand/Context is used.

### Two `Field` Entities — Critical Disambiguation

- **`entity/Field.java`** (~1527 lines): The **crop simulation model**. Contains `_results`, `_weatherData`, `loadAllWeatherDataFromMongo()`, `runModel()`, soil/plant parameters, and irrigation logic. Used by `FieldSimulator` (Mongo pipeline) and `FieldMongoService`.
- **`entity/MongoEntity/Field.java`**: The **MongoDB document** (collection: `field`). Stores field configuration as flat fields (acreage, fieldCapacity, dripRate, autoIrrigation, etc.). Used by `FieldMongoService` for CRUD.

These are completely separate classes. The simulation model is NOT the MongoDB document.

### IoT Data Flow

Persistence and validation are split across two processes connecting to the same private mosquitto broker:

1. **Edge C binaries (`edge/edge_to_mongo_weather.c`, `edge/edge_to_mongo_soil.c`)** — each is a self-contained single `.c` file with all settings hardcoded in a `CONFIG` block at the top (Mongo URI, MQTT broker + creds, `default_group_id`, weather/soil topics, and the `SOIL_FIELDS` topic→`fieldId` table). Pi3 compiles each directly with `cc -O2 <file>.c $(pkg-config --cflags --libs libmongoc-1.0) -lpaho-mqtt3c`. They subscribe to `/sensor/weatherStation2` (weather) and `field1..4` (per-field soil moisture), parse the `key value;key value;...` payload, and insert into MongoDB `sensor_value` via `libmongoc`. Weather rows are keyed by `groupId` only (no `fieldId`); soil rows are keyed by `fieldId` only (no `groupId`) — the field's group is derivable via the `field→group` lookup. The `fieldId` for soil is resolved from the `SOIL_FIELDS` table in `edge_to_mongo_soil.c`. Each row carries `source: "mqtt"`. To add a soil field or rotate creds, edit the constants in the `.c` file and recompile. Setup + run notes live in `edge/README.md`; deploy/systemd in `deploy/DEPLOY.md` §6.
2. **Edge pump controller (`edge/dk_bom_mqtt.c`)** — same standalone-C style, links cJSON from the repo root. Subscribes `cassava/field/+/valve/+/cmd`, parses JSON `OperationCommand`, drives the relay (publishes `1`/`0` to `Pump<valveId>` per the in-source `RELAYS` table), and replies with `OperationAck` on `…/ack`. Spawns one detached pthread per command so long irrigations don't block the broker callback.
3. **BE (`mqtt/MqttSensorListener`)** — subscribes to the same topics on the same broker, parses the same payload, and runs each reading through `service/anomaly/RangeCheckService` (Tier-1 physical-range check). Anomalies are logged (`WARN [sensor] RANGE_FAIL …`); valid readings produce `INFO [sensor] OK …`. **The BE does not write `sensor_value`** — that is solely the edge C job.

Sensor ID mapping (kept in sync across the `WEATHER_MAP` table in `edge/edge_to_mongo_weather.c` and `MqttSensorTopics.resolveSensorId` on the BE): `t→temperature`, `h→relativeHumidity`, `rai→rain`, `rad→radiation`, `w→wind`, plus soil keys `humidity30` (30cm) and `humidity60` (60cm) which pass through verbatim.

Range Check thresholds are now config-driven (`anomaly.range.<sensorId>.{min,max}` in `application*.properties`) instead of hardcoded. Defaults match the previous inline rules: temp [-10,60], relativeHumidity [0,100], rain [0,500], radiation [0,1500], wind [0,50], humidity30/humidity60 [0,100]. Sensors with no configured threshold pass through (no opinion).

Future tiers (Z-score, Seasonal Z-score, ML imputation) per `Anomaly_detection.docx` are out of scope for the current code — the user will design them on top of `RangeCheckService` later.

Note: the sensor formerly named `humidity` was renamed to `relativeHumidity` — check for stale references if touching sensor code.

**Runtime artifact**: The Eclipse Paho MQTT client writes a persistence directory (e.g. `paho<id>-tcp...1883/`) into the repo root when the backend runs. It is not source — already gitignored as `paho*/`.

### Crop Simulation Pipeline

1. `GET /simulation/run?fieldId=X` triggers simulation
2. `FieldSimulator` fetches combined hourly sensor data via `SensorValueService.getCombinedValues(groupId, ...)` (a Mongo aggregation that buckets `sensor_value` rows by hour and averages per sensorId), feeds the resulting CSV-style strings into a `Field` object (from `entity/Field.java`) via `loadAllWeatherDataFromMongo()`, runs the model, and saves results to MongoDB
3. If `autoIrrigation` is true, automatically generates `IrrigationHistory` records
4. `GET /simulation/chart?fieldId=X` returns chart data (labels, yield, irrigation, leafArea, labileCarbon)
5. Results are replaced on each run (`deleteByFieldId` then `saveAll`)
6. DOY-to-Date conversion: anchor-based — uses first weather data entry's actual date and DOY as reference, computes all other dates via `baseDate.plusDays(doy - baseDoy)`. Static fields `previousDoy`/`doyOffset` in `Field.java` must be reset via `resetDoyStaticFields()` before each Mongo data load.

**Irrigation units**: The simulation model internally computes irrigation in **mm** (= L/m²). `FieldSimulator` converts to **m³/ha** (×10) when saving to MongoDB (`simulation_result` and `irrigation_history` collections). Frontend charts and history tables display m³/ha.

### Key External Dependencies

- **MongoDB**: Remote instance at `112.137.129.218:27017`, database `iot_agriculture`
- **MQTT (single private mosquitto broker)**: serves both the operation subsystem (irrigation cmd/ack) and the sensor subsystem (weather + soil). URL configurable via `mqtt.operation.broker-url` (dev: `tcp://112.137.129.218:1883`; prod: `tcp://127.0.0.1:1883`, mosquitto runs on the same host as the BE). Auto-reconnect enabled — BE starts even if the broker is offline. Edge auth: `libe / 123456` (set via `mqtt.operation.username/password` in prod profile).

## Production Deployment

Deployed at `http://112.137.129.218/` on a UET-managed Ubuntu server (user `uet`). Single-port nginx (80) fans traffic path-based:

```
/               → /opt/cassava/webroot/index.html  (landing page: 4 crop cards)
/cassava/       → /opt/cassava/webroot/cassava/    (CassavaFE SPA, base=/cassava/)
/cassava/api/*  → proxy_pass http://127.0.0.1:8081/  (Spring Boot, loopback-only)
```

Key artifacts (all committed under `deploy/`):
- `deploy/nginx/cassava.conf` — site config; proxy trailing slash strips `/cassava/api/`; `proxy_read_timeout 600s` for long simulations
- `deploy/systemd/cassava-be.service` — runs `java -jar /opt/cassava/cassava-be.war` as `uet`, sets `SPRING_PROFILES_ACTIVE=prod`
- `deploy/DEPLOY.md` — full build/upload/install runbook + troubleshooting
- `deploy/MQTT.md` — operation MQTT architecture, payload schemas, schedule lifecycle, mosquitto setup, edge subscriber spec
- `cassavaBE/src/main/resources/application-prod.properties` — activated by prod profile; binds `server.address=127.0.0.1`, logs to `/var/log/cassava/app.log`, points operation MQTT to `tcp://127.0.0.1:1883`
- `landing/` — static HTML landing page with UET logo and one card per crop (cassava active, others "Sắp ra mắt")

FE build path: `vite.config.js` sets `base: '/cassava/'` only when `mode === 'production'`; `App.jsx` passes `basename={import.meta.env.BASE_URL}` into `BrowserRouter` so client-side routes resolve correctly under the subpath. Assets must be imported (`import logoUet from '.../logo-uet.png'`) — never reference `/src/...` paths, which only work in dev.

Update flow: FE-only changes → rebuild + rsync `dist/` to `/opt/cassava/webroot/cassava/`, no restart. BE-only changes → rebuild WAR, rsync, `systemctl restart cassava-be`.

## Git Remote

- **Repository**: `git@github.com:trieu-1802/CASSAVA_IOT.git`
- **Branch**: `master`

## Conventions

- Backend packaging is WAR (servlet-based with embedded Tomcat)
- Java 17 required
- Frontend API calls use four separate Axios instances — check which one matches the endpoint prefix before adding new API calls
- MongoDB collections: `field`, `field_group`, `field_group_sensor`, `field_sensor`, `sensor_value`, `simulation_result`, `irrigation_history`, `irrigation_schedule`, `users`, `diseases`
- Both MQTT subsystems (operation + sensor anomaly) share a single Paho client bean `MqttConfig.operationMqttClient()` against the private mosquitto broker. Edge Python scripts open their own client connection to the same broker for `sensor_value` persistence.
- Sensor persistence is owned by the **edge C binaries** (`edge/edge_to_mongo_weather.c` + `edge/edge_to_mongo_soil.c`); the Java BE never writes `sensor_value` rows. If you find Java code that does, it's drift — investigate before adding more.
