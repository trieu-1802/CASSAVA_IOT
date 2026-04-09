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
mvn test              # run tests
```

## Architecture

```
React SPA (5173) ──Axios──▶ Spring Boot API (8081) ──▶ MongoDB (remote)
                                    │
                          ┌─────────┼─────────┐
                       MQTT       NASA API   Firebase
                    (HiveMQ)    (weather)   (storage)
```

Main app (`Demo1Application`) uses `@EnableCaching` and `@EnableScheduling`. A scheduled task in `FieldService` auto-calculates the crop model daily at 7:30 AM for Firebase-based fields.

### Backend (`cassavaBE/src/main/java/com/example/demo/`)

- **controller/**: REST endpoints:
  - `FieldMongoController` (`/mongo/field`) — MongoDB field CRUD + clone
  - `FieldSensorController` (`/mongo/field/{fieldId}/sensor`) — sensor-to-field mapping
  - `SensorValueController` (`/sensor-values`) — sensor history and combined values
  - `IrrigationHistoryController` (`/mongo/irrigation-history`) — irrigation records
  - `SimulationController` (`/simulation`) — run/chart simulation
  - `UserController` (`/api/auth`) — login, register, list users
  - Legacy: `FieldController` (`/api/*` — Firebase-based), `NasaController` (`/api/nasa`), `ChartController` (`/api/chart-data`)
- **service/**: Business logic — `FieldMongoService`, `MqttWeatherService` (MQTT subscriber + NASA fallback), `SensorValueService`, `FieldSimulator` (crop simulation + auto irrigation history), `IrrigationHistoryService`, `NasaBackupService`
- **entity/**: Two separate `Field` classes (see disambiguation below), plus `User`, `FieldSensor`, `SensorValue`, `FieldSimulationResult`, `IrrigationHistory`, `Disease`; legacy Firebase entities also in `entity/` (non-Mongo)
- **repositories/**: Spring Data MongoDB repos — includes `FieldSimulationResultRepository`, `IrrigationHistoryRepository`
- **Jwt/**: `JwtUtils` (token gen/validation), `JwtAuthFilter` (request filter)
- **config/**: `SecurityConfig` (CORS + auth rules), `WebConfig`
- **firebase/**: Firebase Realtime Database integration, `CorsConfig` (see CORS note below)

Auth flow: JWT with 24h expiry (HS512). Roles are ADMIN and USER. Public endpoints: `/api/auth/**` only; `/mongo/**` and `/simulation/**` are permitAll. Token injected via `JwtAuthFilter` in the Spring Security filter chain.

**CORS gotcha**: `SecurityConfig` restricts CORS to `http://localhost:5173`, but `firebase/CorsConfig.java` has a separate `@Bean` allowing ALL origins for `/api/**`. Be aware of this dual configuration.

### Frontend (`CassavaFE/src/`)

React 19 + Vite, Ant Design v6, React Router v7, Recharts for charts.

- **pages/**: Login, Register, FieldList, FieldDetail (4 tabs: disease, irrigation, yield, history), WeatherDashboard, WeatherDetail, UserList
- **services/**: Three separate Axios instances with different base URLs:
  - `api.js` → `http://localhost:8081` (general API, JWT interceptor)
  - `authService.js` → `http://localhost:8081/api` (auth endpoints, JWT interceptor)
  - `fieldService.js` → `http://localhost:8081/mongo` (field/mongo endpoints, JWT interceptor)
- **components/**: `MainLayout` (responsive sidebar — uses `Drawer` on mobile <768px, collapsible `Sider` on desktop), `FieldModal`, `SimulationDashboard`
- **utils/**: `exportExcel.js`, `formatters.js` — **both are empty stubs**
- **contexts/**: `AuthContext.jsx` exists but is **empty/unused**
- **routes/**: `PrivateRoute.jsx` exists but is **empty/unused** — no actual route protection

Auth state is managed entirely via `localStorage` (key: `user` with `accessToken` and `isAdmin` fields). No Redux/Zustand/Context is used.

### Two `Field` Entities — Critical Disambiguation

- **`entity/Field.java`** (~1527 lines): The **crop simulation model**. Contains `_results`, `_weatherData`, `loadAllWeatherDataFromMongo()`, `runModel()`, soil/plant parameters, and irrigation logic. Used by `FieldSimulator` and `FieldService`.
- **`entity/MongoEntity/Field.java`**: The **MongoDB document** (collection: `field`). Stores field configuration as flat fields (acreage, fieldCapacity, dripRate, autoIrrigation, etc.). Used by `FieldMongoService` for CRUD.

These are completely separate classes. The simulation model is NOT the MongoDB document.

### IoT Data Flow

MQTT broker (HiveMQ `broker.hivemq.com:1883`) → topic `/sensor/weatherStation` → `MqttWeatherService` validates and persists JSON sensor readings to `sensor_value` collection. Falls back to NASA Power API if MQTT data is invalid, with a 10-minute cooldown between NASA calls.

Sensor ID mapping: `t`→temperature, `h`→humidity, `rai`→rain, `rad`→radiation, `w`→wind.

Validation ranges: temp -10–60°C, humidity 0–100%, rain 0–500mm, wind 0–50m/s. Values outside these ranges trigger the NASA backup fallback.

### Crop Simulation Pipeline

1. `GET /simulation/run?fieldId=X` triggers simulation
2. `FieldSimulator` fetches combined sensor data via `SensorValueService.getCombinedValues()`, creates a `Field` object (from `entity/Field.java`), loads weather data, runs the model, saves results to MongoDB
3. If `autoIrrigation` is true, automatically generates `IrrigationHistory` records
4. `GET /simulation/chart?fieldId=X` returns chart data (labels, yield, irrigation, leafArea, labileCarbon)
5. Results are replaced on each run (`deleteByFieldId` then `saveAll`)
6. DOY-to-Date conversion: anchor-based — uses first weather data entry's actual date and DOY as reference, computes all other dates via `baseDate.plusDays(doy - baseDoy)`. Static fields `previousDoy`/`doyOffset` in `Field.java` must be reset via `resetDoyStaticFields()` before each Mongo data load.

**Irrigation units**: The simulation model internally computes irrigation in **mm** (= L/m²). `FieldSimulator` converts to **m³/ha** (×10) when saving to MongoDB (`simulation_result` and `irrigation_history` collections). Frontend charts and history tables display m³/ha.

### Key External Dependencies

- **MongoDB**: Remote instance at `112.137.129.218:27017`, database `iot_agriculture`
- **Firebase**: Service account key expected at `D:\DATN\serviceAccountKey.json` (hardcoded Windows path in `FirebaseInitialization`)
- **MQTT**: HiveMQ public broker
- **APIs**: NASA Power (no key), OpenWeather (key in `MqttWeatherService`)

## Git Remote

- **Repository**: `git@github.com:trieu-1802/IOT_Agriculture.git`
- **Branch**: `master`

## Conventions

- Backend packaging is WAR (servlet-based with embedded Tomcat)
- Java 17 required
- Frontend API calls use three separate Axios instances — check which one matches the endpoint prefix before adding new API calls
- MongoDB collections: `field`, `users`, `sensor_value`, `field_sensor`, `simulation_result`, `irrigation_history`, `diseases`
