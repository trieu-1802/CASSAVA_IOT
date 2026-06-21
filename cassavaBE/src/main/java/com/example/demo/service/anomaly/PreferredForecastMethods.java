package com.example.demo.service.anomaly;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Picks which forecaster supplies the recovery / imputation value (the
 * {@code predicted} written to a SensorCorrection), per sensor — decoupled
 * from the anomaly decision in {@link PreferredDetectionMethods}.
 *
 * The /detect response exposes each forecaster's one-step prediction inside
 * its residual-detector entry ({@code "<model>_residual".predicted} == that
 * model's forecast), so the value stored here is the /detect method name to
 * read {@code predicted} from.
 *
 * Default is SARIMA for every sensor — it wins one-step forecast + recovery
 * (lowest recovery MAE) across the 4-season backtest; rain is the only sensor
 * where ARIMA edges it, but SARIMA stays the simple year-round default.
 *
 * Overrides come from {@code ml.forecast.preferred-method.<sensorId>} properties.
 */
@Component
public class PreferredForecastMethods {

    private static final Logger log = LoggerFactory.getLogger(PreferredForecastMethods.class);

    private static final Map<String, String> DEFAULTS = Map.of(
            "temperature",      "sarima_residual",
            "relativeHumidity", "sarima_residual",
            "rain",             "sarima_residual",
            "radiation",        "sarima_residual",
            "wind",             "sarima_residual"
    );

    @Value("${ml.forecast.preferred-method.temperature:}")      private String temperature;
    @Value("${ml.forecast.preferred-method.relativeHumidity:}") private String relativeHumidity;
    @Value("${ml.forecast.preferred-method.rain:}")             private String rain;
    @Value("${ml.forecast.preferred-method.radiation:}")        private String radiation;
    @Value("${ml.forecast.preferred-method.wind:}")             private String wind;

    private final Map<String, String> resolved = new HashMap<>();

    @PostConstruct
    public void init() {
        resolved.put("temperature",      pick("temperature", temperature));
        resolved.put("relativeHumidity", pick("relativeHumidity", relativeHumidity));
        resolved.put("rain",             pick("rain", rain));
        resolved.put("radiation",        pick("radiation", radiation));
        resolved.put("wind",             pick("wind", wind));
        log.info("Preferred forecast (recovery) methods: {}", resolved);
    }

    public String forSensor(String sensorId) {
        return resolved.get(sensorId);
    }

    private static String pick(String sensor, String override) {
        return (override != null && !override.isBlank()) ? override.trim() : DEFAULTS.get(sensor);
    }
}
