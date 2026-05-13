package com.example.demo.service.anomaly;

import com.example.demo.entity.MongoEntity.SensorValue;
import com.example.demo.repositories.mongo.SensorValueRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seasonal z-score detector. Buckets historical sensor_value rows by hour-of-day
 * (UTC, matching the ml-service benchmark) and computes a sample mean/std per
 * bucket. A live reading is anomalous when |(value - μ_hour) / σ_hour| > k.
 *
 * Ported from ml-service/ml/detectors/seasonal_zscore.py — same algorithm,
 * same k=3.0 default (best F1 in the empirical comparison).
 *
 * Fit scope is global per sensorId (one model spanning all groups/fields). This
 * is the MVP; per-group/per-field models can be added later if microclimates
 * diverge enough that the global model has too many false positives.
 */
@Service
public class SeasonalZScoreService {

    private static final Logger log = LoggerFactory.getLogger(SeasonalZScoreService.class);

    @Autowired
    private SensorValueRepository sensorValueRepository;

    @Value("${anomaly.zscore.k:3.0}")
    private double k;

    @Value("${anomaly.zscore.history-days:30}")
    private int historyDays;

    @Value("${anomaly.zscore.min-samples-per-bucket:10}")
    private int minSamplesPerBucket;

    @Value("${anomaly.zscore.sensors:temperature,relativeHumidity,rain,radiation,wind,humidity30,humidity60}")
    private String[] sensors;

    // sensorId → 24-slot array. slot[h] = [μ, σ] for hour h; null if bucket has < min samples.
    private final Map<String, double[][]> stats = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        try {
            fitAll();
        } catch (Exception e) {
            log.warn("[zscore] initial fit failed: {}", e.getMessage());
        }
    }

    /** Daily refit at 02:30 local time (cron default). Cheap: O(N) over ~30 days of hourly rows. */
    @Scheduled(cron = "${anomaly.zscore.refit-cron:0 30 2 * * *}")
    public void fitAll() {
        Date start = Date.from(Instant.now().minus(historyDays, ChronoUnit.DAYS));
        Date end = new Date();
        for (String sensorId : sensors) {
            fit(sensorId.trim(), start, end);
        }
    }

    private void fit(String sensorId, Date start, Date end) {
        if (sensorId.isEmpty()) return;

        List<SensorValue> rows = sensorValueRepository
                .findBySensorIdAndTimeBetweenOrderByTimeAsc(sensorId, start, end);
        if (rows.isEmpty()) {
            log.info("[zscore] fit {}: no data in last {} days", sensorId, historyDays);
            stats.remove(sensorId);
            return;
        }

        // Running sums per hour bucket: [Σx, Σx², count]
        double[][] sums = new double[24][3];
        for (SensorValue v : rows) {
            int hour = v.getTime().toInstant().atZone(ZoneOffset.UTC).getHour();
            double x = v.getValue();
            sums[hour][0] += x;
            sums[hour][1] += x * x;
            sums[hour][2] += 1;
        }

        double[][] bucket = new double[24][];
        int fittedBuckets = 0;
        for (int h = 0; h < 24; h++) {
            int n = (int) sums[h][2];
            if (n < minSamplesPerBucket) continue;
            double mean = sums[h][0] / n;
            // Sample variance (Bessel's correction) to match pandas .std() default in ml-service
            double variance = n > 1 ? (sums[h][1] - n * mean * mean) / (n - 1) : 0.0;
            double sigma = Math.sqrt(Math.max(0.0, variance));
            bucket[h] = new double[]{mean, sigma};
            fittedBuckets++;
        }

        if (fittedBuckets == 0) {
            log.info("[zscore] fit {}: no bucket has ≥{} samples (rows={})",
                    sensorId, minSamplesPerBucket, rows.size());
            stats.remove(sensorId);
            return;
        }

        stats.put(sensorId, bucket);
        log.info("[zscore] fit {}: {} of 24 buckets, {} rows over {} days",
                sensorId, fittedBuckets, rows.size(), historyDays);
    }

    public ZScoreResult score(String sensorId, double value, Instant time) {
        double[][] sensorStats = stats.get(sensorId);
        if (sensorStats == null) return ZScoreResult.notFitted();

        int hour = time.atZone(ZoneOffset.UTC).getHour();
        double[] b = sensorStats[hour];
        if (b == null) return ZScoreResult.notFitted();

        double mu = b[0];
        double sigma = b[1];
        if (sigma < 1e-9) return ZScoreResult.constant(mu);

        double z = (value - mu) / sigma;
        return Math.abs(z) > k
                ? ZScoreResult.anomaly(mu, sigma, z)
                : ZScoreResult.ok(mu, sigma, z);
    }

    public double getK() { return k; }
}
