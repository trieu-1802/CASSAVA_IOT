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

### Backend (`cassavaBE/src/main/java/com/example/demo/`)

- **controller/**: REST endpoints — `FieldMongoController` (`/mongo/field`), `UserController` (`/api/auth`), `SensorValueController` (`/api/sensor-values`), `SimulationController` (`/simulation`), `IrrigationHistoryController` (`/mongo/irrigation-history`)
- **service/**: Business logic — `FieldMongoService`, `MqttWeatherService` (MQTT subscriber + NASA fallback), `SensorValueService`, `FieldSimulator` (crop simulation + auto irrigation history), `IrrigationHistoryService`
- **entity/**: MongoDB document models — `Field` (with `Field(String name)` default constructor for fieldTest init), `User`, `FieldSensor`, `SensorValue`, `FieldSimulationResult`, `IrrigationHistory`; legacy Firebase entities also in `entity/` (non-Mongo)
- **repositories/**: Spring Data MongoDB repos — includes `FieldSimulationResultRepository`, `IrrigationHistoryRepository`
- **Jwt/**: `JwtUtils` (token gen/validation), `JwtAuthFilter` (request filter)
- **config/**: `SecurityConfig` (CORS + auth rules), `WebConfig`
- **firebase/**: Firebase Realtime Database integration

Auth flow: JWT with 24h expiry. Roles are ADMIN and USER. Public endpoints: `/api/auth/**` only. Token injected via `JwtAuthFilter` in the Spring Security filter chain.

### Frontend (`CassavaFE/src/`)

- **pages/**: Login, Register, FieldList, FieldDetail (4 tabs: disease, irrigation, yield, history), WeatherDashboard, WeatherDetail, UserList
- **services/**: API clients (`api.js` has Axios interceptor for JWT, `fieldService.js`, `authService.js`, `weatherService.js`)
- **contexts/**: `AuthContext` for global auth state
- **routes/**: Route config with `PrivateRoute` protection
- **components/**: `MainLayout` (sidebar+header), `FieldModal`
- **utils/**: `exportExcel.js` (Excel export), `formatters.js`

UI framework: Ant Design. Routing: React Router DOM.

### IoT Data Flow

MQTT broker (HiveMQ `broker.hivemq.com:1883`) → topic `/sensor/weatherStation` → `MqttWeatherService` validates and persists JSON sensor readings (`t`, `h`, `rai`, `rad`, `w`) to `sensor_value` collection. Falls back to NASA Power API if MQTT data is invalid, with a 10-minute cooldown between NASA calls.

### Key External Dependencies

- **MongoDB**: Remote instance at `112.137.129.218:27017`, database `iot_agriculture`
- **Firebase**: Service account key expected at `serviceAccountKey.json` (path configured in `FirebaseInitializer`)
- **MQTT**: HiveMQ public broker
- **APIs**: NASA Power (no key), OpenWeather (key in `MqttWeatherService`)

### MongoDB Field Entity (`entity/MongoEntity/Field.java`)

The MongoDB `Field` entity stores all customized parameters as flat individual fields (acreage, fieldCapacity, distanceBetweenRow, distanceBetweenHole, dripRate, autoIrrigation, numberOfHoles, fertilizationLevel, irrigationDuration, scaleRain) rather than a nested `CustomizedParameters` object. This differs from the legacy Firebase `Field` entity which uses `CustomizedParameters customized_parameters`.

- `Field(String name)` constructor initializes all flat fields with defaults matching the Firebase `CustomizedParameters(name)` defaults (acreage=50, fieldCapacity=60, dripRate=1.6, etc.)
- `Field(String id, double acreage, ...)` full constructor for explicit values
- `Field()` no-arg constructor for MongoDB/Jackson deserialization

### Crop Simulation Pipeline

The simulation feature runs a cassava crop growth model using sensor data from MongoDB:

1. `SimulationController` exposes two endpoints:
   - `GET /simulation/run?fieldId=X` — triggers simulation for a field
   - `GET /simulation/chart?fieldId=X` — returns chart data (labels, yield, irrigation, leafArea) from saved results
2. `FieldSimulator` service orchestrates: fetches combined sensor data via `SensorValueService.getCombinedValues()`, creates a `Field` object (from `entity/Field.java`, the simulation model), loads weather data, runs the model, saves results to MongoDB, and **automatically saves irrigation history** when `autoIrrigation` is true
3. `FieldSimulationResult` (collection: `simulation_result`) stores per-day results: fieldId, time, yield, irrigation, leafArea, labileCarbon
4. `IrrigationHistory` (collection: `irrigation_history`) stores per-day irrigation records: fieldId, time, userName, amount (l/m2), duration (seconds). Computed from daily differences of cumulative irrigation in `results.get(2)`, matching Firebase `HistoryIrrigation` logic
5. Results and irrigation history are replaced on each run (`deleteByFieldId` then `saveAll`)
6. DOY-to-Date conversion: `year = 2023 + (doy-1)/365`, `dayOfYear = (doy-1)%365 + 1`

**Important:** The simulation uses `entity/Field.java` (the crop model with `_results`, `loadAllWeatherDataFromMongo()`, `runModel()`), NOT `entity/MongoEntity/Field.java` (the MongoDB document).

### Irrigation History API

REST API for irrigation history records (collection: `irrigation_history`), mirroring Firebase `HistoryIrrigation`:

- `GET /mongo/irrigation-history?fieldId=X` — list all history for a field (newest first)
- `POST /mongo/irrigation-history` — create a record manually (body: `{ fieldId, time, userName, amount, duration }`)
- `DELETE /mongo/irrigation-history/{id}` — delete one record
- `DELETE /mongo/irrigation-history/field/{fieldId}` — delete all records for a field

Records are also created automatically by `FieldSimulator` when running `/simulation/run`. Security: covered by `/mongo/**` permitAll rule.

## Git Remote

- **Repository**: `git@github.com:trieu-1802/IOT_Agriculture.git`
- **Branch**: `master`

## Conventions

- Backend packaging is WAR (servlet-based with embedded Tomcat)
- Java 17 required
- CORS is configured to allow only `http://localhost:5173`
- Frontend API base URL: `http://localhost:8081/api` (configured in `CassavaFE/src/services/api.js`)
