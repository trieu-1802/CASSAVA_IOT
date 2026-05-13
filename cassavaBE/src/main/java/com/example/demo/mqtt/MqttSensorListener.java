package com.example.demo.mqtt;

import com.example.demo.service.anomaly.SeasonalZScoreService;
import com.example.demo.service.anomaly.ZScoreResult;
import jakarta.annotation.PostConstruct;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class MqttSensorListener {

    private static final Logger log = LoggerFactory.getLogger(MqttSensorListener.class);

    @Autowired
    private MqttClient mqttClient;

    @Autowired
    private SeasonalZScoreService zscore;

    @Value("${mqtt.sensor.weather-topic:/sensor/weatherStation2}")
    private String weatherTopic;

    @Value("${mqtt.sensor.soil-topics:field1,field2,field3,field4,field2.1,field4.1}")
    private String[] soilTopics;

    @PostConstruct
    public void subscribe() {
        subscribeTopic(weatherTopic, true);
        for (String t : soilTopics) {
            String topic = t.trim();
            if (!topic.isEmpty()) subscribeTopic(topic, false);
        }
    }

    private void subscribeTopic(String topic, boolean isWeather) {
        try {
            mqttClient.subscribe(topic, 1, (rcvTopic, message) -> handle(rcvTopic, message.getPayload(), isWeather));
            log.info("MQTT sensor subscribed: {} (weather={})", topic, isWeather);
        } catch (Exception e) {
            log.warn("MQTT sensor subscribe failed for {}: {}", topic, e.getMessage());
        }
    }

    private void handle(String topic, byte[] payload, boolean isWeather) {
        String body = new String(payload).trim();
        if (body.isEmpty()) return;

        Instant now = Instant.now();
        for (String pair : body.split(";")) {
            String[] kv = pair.trim().split("\\s+");
            if (kv.length < 2) continue;

            String sensorId = isWeather ? MqttSensorTopics.resolveSensorId(kv[0]) : kv[0];
            double value;
            try {
                value = Double.parseDouble(kv[1]);
            } catch (NumberFormatException e) {
                log.warn("[sensor] PARSE_FAIL topic={} pair='{}' err={}", topic, pair, e.getMessage());
                continue;
            }

            ZScoreResult r = zscore.score(sensorId, value, now);
            switch (r.getStatus()) {
                case ANOMALY -> log.warn(
                        "[sensor] Z_FAIL topic={} sensorId={} value={} z={} mu={} sigma={} k={}",
                        topic, sensorId, value, fmt(r.getZ()), fmt(r.getMu()), fmt(r.getSigma()), zscore.getK());
                case OK -> log.info(
                        "[sensor] OK topic={} sensorId={} value={} z={}",
                        topic, sensorId, value, fmt(r.getZ()));
                case CONSTANT -> log.info(
                        "[sensor] OK topic={} sensorId={} value={} (constant μ={})",
                        topic, sensorId, value, fmt(r.getMu()));
                case NOT_FITTED -> log.info(
                        "[sensor] OK topic={} sensorId={} value={} (no model yet)",
                        topic, sensorId, value);
            }
        }
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }
}
