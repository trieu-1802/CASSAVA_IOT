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
                                    │
                          ┌─────────┼─────────┐
                       MQTT       NASA API   Firebase
                    (HiveMQ)    (weather)   (storage)
```

Main app (`Demo1Application`) uses `@EnableCaching` and `@EnableScheduling`. `FieldSimulator.runScheduledSimulationForAllFields()` runs the crop simulation for every Mongo field twice daily at **07:00 and 17:00 `Asia/Ho_Chi_Minh`** (cron `0 0 7,17 * * *`). On-demand runs are still available via `GET /simulation/run?fieldId=X`.

### Backend (`cassavaBE/src/main/java/com/example/demo/`)

- **controller/**: REST endpoints. `UserController` lives at the root; the rest are under `controller/mongo/`:
  - `FieldMongoController` (`/mongo/field`) — MongoDB field CRUD, plus `POST /mongo/field/clone/{id}` (deep-copy) and `POST /mongo/field/resetCrop/{id}` (new growing cycle)
  - `FieldGroupController` (`/mongo/field-group`) — CRUD for field groups (groups share a weather station; every field must belong to one)
  - `FieldGroupSensorController` — sensor-to-group mapping (weather station sensors shared across a group's fields)
  - `FieldSensorController` (`/mongo/field/{fieldId}/sensor`) — per-field sensor mapping (soil moisture sensors)
  - `SensorValueController` (`/sensor-values`) — sensor history and combined values
  - `IrrigationHistoryController` (`/mongo/irrigation-history`) — irrigation record log
  - `IrrigationScheduleController` (`/mongo/irrigation-schedule`) — manual irrigation scheduling (PENDING/RUNNING/DONE/CANCELLED/FAILED lifecycle)
  - `SimulationController` (`/simulation`) — run/chart simulation
  - `UserController` (`/api/auth`) — login, register, list users
- **service/**: Split between the root and a `Mongo/` subpackage.
  - `service/`: `JwtService`, `MqttWeatherService` (MQTT subscriber + NASA fallback), `NasaBackupService`, `UserService`
  - `service/Mongo/`: `FieldMongoService`, `FieldGroupService`, `FieldGroupSensorService`, `FieldSensorService`, `FieldSimulator` (crop simulation + auto irrigation history), `SensorValueService`, `IrrigationHistoryService`, `IrrigationScheduleService`, plus the `WeatherProvider` interface and its `MongoWeatherProvider` implementation
  - **Empty placeholders** (do not confuse with real services): `service/Mongo/IrrigationService.java` and `mqtt/MqttConfig.java` are empty class stubs — no logic yet
- **entity/**: Two separate `Field` classes (see disambiguation below), plus `User`, `FieldSensor`, `FieldGroup`, `FieldGroupSensor`, `SensorValue`, `FieldSimulationResult`, `IrrigationHistory`, `IrrigationSchedule`, `Disease`; some leftover Firebase-era DTOs/entities still live in `entity/` (e.g. `FieldDTO`, `CustomizedParameters`, `Humidity`, `MeasuredData`, `WeatherRequest`, `ChartData`, `HistoryIrrigation`) because the simulation model `entity/Field.java` still depends on several of them
- **repositories/**: Spring Data MongoDB repos under `repositories/mongo/` — includes `FieldGroupRepository`, `FieldGroupSensorRepository`, `FieldSimulationResultRepository`, `IrrigationHistoryRepository`, `IrrigationScheduleRepository`

**Field ↔ Group constraint**: `FieldMongoService.create()` rejects a field unless `groupId` references an existing `field_group`. Every field belongs to exactly one group; groups are the unit of weather-station sharing.

**Cascade delete**: `FieldMongoService.delete()` clears `field_sensor`, `sensor_value`, `simulation_result`, `irrigation_history`, and `irrigation_schedule` rows tied to the field before removing the field document. Any new field-scoped collection must be added here to avoid orphaned data.

**Reset crop**: `POST /mongo/field/resetCrop/{id}` resets per-crop state on the `Field` Mongo document (startTime, DAP=1, irrigating=false) for a new growing cycle. Note: per-crop history (`simulation_result`, `irrigation_history`) is **retained** and distinguished by `cropStartTime` — it is not cleared on reset.

**Auto vs. manual irrigation**: A field's `autoIrrigation` flag is mutually exclusive with manual scheduling. `IrrigationScheduleService.create()` rejects new schedules while `autoIrrigation=true`, and requires a valid `valveId` in the range 1–4 (either on the schedule or inherited from the field).
- **Jwt/**: `JwtUtils` (token gen/validation), `JwtAuthFilter` (request filter)
- **config/**: `SecurityConfig` (CORS + auth rules), `WebConfig`
- **firebase/**: Firebase Realtime Database integration, `CorsConfig` (see CORS note below)

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
- **services/**: Four Axios instances, each with `baseURL` built from `import.meta.env.VITE_API_BASE` (fallback `http://localhost:8081`), plus one empty stub:
  - `api.js` → `${VITE_API_BASE}` — injects `Authorization: Bearer <token>` from `localStorage.user.accessToken`
  - `authService.js` → `${VITE_API_BASE}/api` — same `Authorization: Bearer` interceptor
  - `fieldService.js` → `${VITE_API_BASE}/mongo` — JWT interceptor attached; endpoints are `permitAll` in `SecurityConfig` but the token is still sent
  - `groupService.js` → `${VITE_API_BASE}/mongo/field-group`
  - `weatherService.js` — empty stub
- **components/**: `Layout/MainLayout` only (responsive sidebar — `Drawer` on mobile <768px, collapsible `Sider` on desktop). Feature-specific components live under their `pages/<feature>/components/` folder.
- **utils/**: `exportExcel.js`, `formatters.js` — **both are empty stubs**
- **contexts/**: `AuthContext.jsx` exists but is **empty/unused**
- **routes/**: `PrivateRoute.jsx` and `AppRoutes.jsx` exist but are **empty/unused** — no actual route protection; routing is defined inline in `App.jsx` (e.g. `/fields`, `/fields/:id`, `/fields/:fieldId/soil-sensor`, `/field-groups`, `/weather`, `/weather/:groupId`)

Auth state is managed entirely via `localStorage` (key: `user` with `accessToken` and `isAdmin` fields). No Redux/Zustand/Context is used.

### Two `Field` Entities — Critical Disambiguation

- **`entity/Field.java`** (~1527 lines): The **crop simulation model**. Contains `_results`, `_weatherData`, `loadAllWeatherDataFromMongo()`, `runModel()`, soil/plant parameters, and irrigation logic. Used by `FieldSimulator` (Mongo pipeline) and `FieldMongoService`.
- **`entity/MongoEntity/Field.java`**: The **MongoDB document** (collection: `field`). Stores field configuration as flat fields (acreage, fieldCapacity, dripRate, autoIrrigation, etc.). Used by `FieldMongoService` for CRUD.

These are completely separate classes. The simulation model is NOT the MongoDB document.

### IoT Data Flow

MQTT broker (HiveMQ `broker.hivemq.com:1883`) → topic `/sensor/weatherStation` → `MqttWeatherService` validates and persists JSON sensor readings to `sensor_value` collection. Falls back to NASA Power API if MQTT data is invalid, with a 10-minute cooldown between NASA calls.

Sensor ID mapping: `t`→temperature, `h`→relativeHumidity, `rai`→rain, `rad`→radiation, `w`→wind, plus soil moisture sensors `humidity30` (30cm depth) and `humidity60` (60cm depth). When a field is created, `FieldSensorService` auto-initializes the 7 default sensors for it.

Validation ranges: temp -10–60°C, humidity 0–100%, rain 0–500mm, wind 0–50m/s. Values outside these ranges trigger the NASA backup fallback.

Note: the sensor formerly named `humidity` was renamed to `relativeHumidity` — check for stale references if touching sensor code.

**Runtime artifact**: The Eclipse Paho MQTT client writes a persistence directory (e.g. `paho<id>-tcpbrokerhivemqcom1883/`) into the repo root when the backend runs. It is not source — ignore it and it should be gitignored.

### Crop Simulation Pipeline

1. `GET /simulation/run?fieldId=X` triggers simulation
2. `FieldSimulator` fetches combined sensor data via `SensorValueService.getCombinedValues()`, creates a `Field` object (from `entity/Field.java`), loads weather data through the `WeatherProvider` interface (`service/Mongo/WeatherProvider.java`, currently implemented by `MongoWeatherProvider` reading from the `sensor_value` collection), runs the model, and saves results to MongoDB
3. If `autoIrrigation` is true, automatically generates `IrrigationHistory` records
4. `GET /simulation/chart?fieldId=X` returns chart data (labels, yield, irrigation, leafArea, labileCarbon)
5. Results are replaced on each run (`deleteByFieldId` then `saveAll`)
6. DOY-to-Date conversion: anchor-based — uses first weather data entry's actual date and DOY as reference, computes all other dates via `baseDate.plusDays(doy - baseDoy)`. Static fields `previousDoy`/`doyOffset` in `Field.java` must be reset via `resetDoyStaticFields()` before each Mongo data load.

**Irrigation units**: The simulation model internally computes irrigation in **mm** (= L/m²). `FieldSimulator` converts to **m³/ha** (×10) when saving to MongoDB (`simulation_result` and `irrigation_history` collections). Frontend charts and history tables display m³/ha.

### Key External Dependencies

- **MongoDB**: Remote instance at `112.137.129.218:27017`, database `iot_agriculture`
- **Firebase**: Service account key looked up at `D:\DATN\serviceAccountKey.json` (Windows dev) and `/opt/cassava/serviceAccountKey.json` (Linux prod). `FirebaseInitialization` returns `null` gracefully if the file is missing; call sites in `NasaBackupService` and `entity/Field.java` skip uploads when the bean is null rather than throwing.
- **MQTT**: HiveMQ public broker
- **APIs**: NASA Power (no key), OpenWeather (key in `MqttWeatherService`)

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
- `cassavaBE/src/main/resources/application-prod.properties` — activated by prod profile; binds `server.address=127.0.0.1`, logs to `/var/log/cassava/app.log`
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
