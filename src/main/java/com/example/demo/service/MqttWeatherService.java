package com.example.demo.service;

import com.example.demo.entity.MongoEntity.FieldSensor;
import com.example.demo.entity.MongoEntity.SensorValue;
import com.example.demo.repositories.mongo.FieldSensorRepository;
import com.example.demo.repositories.mongo.SensorValueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MqttWeatherService {

    @Autowired
    private SensorValueRepository sensorValueRepository;

    @Autowired
    private FieldSensorRepository fieldSensorRepository;

    @Autowired
    private NasaBackupService nasaBackupService;

    private static final String BROKER = "tcp://broker.hivemq.com:1883";
    private static final String TOPIC = "/sensor/weatherStation";

    private long lastBackupCall = 0;

    // ========================
    // PARSE SAFE
    // ========================
    private double parse(Object o) {
        return o == null ? 0 : Double.parseDouble(o.toString());
    }

    // ========================
    // SAVE WITH VALIDATION
    // ========================
    private void saveSensorValues(Map<String, Object> data) {

        try {
            String fieldId = "fieldTest";
            String deviceId = "weatherStation1";
            Date now = new Date();

            // 🔥 load sensor hợp lệ 1 lần
            List<String> validSensors = fieldSensorRepository
                    .findByFieldId(fieldId)
                    .stream()
                    .map(FieldSensor::getSensorId)
                    .collect(Collectors.toList());

            List<SensorValue> list = new ArrayList<>();

            // ========================
            // MAPPING + VALIDATE
            // ========================
            if (validSensors.contains("temperature") && data.get("t") != null) {
                list.add(new SensorValue(fieldId, "temperature", parse(data.get("t")), now));
            }

            if (validSensors.contains("humidity") && data.get("h") != null) {
                list.add(new SensorValue(fieldId, "humidity", parse(data.get("h")), now));
            }

            if (validSensors.contains("rain") && data.get("rai") != null) {
                list.add(new SensorValue(fieldId, "rain", parse(data.get("rai")), now));
            }

            if (validSensors.contains("radiation") && data.get("rad") != null) {
                list.add(new SensorValue(fieldId, "radiation", parse(data.get("rad")), now));
            }

            if (validSensors.contains("wind") && data.get("w") != null) {
                list.add(new SensorValue(fieldId, "wind", parse(data.get("w")), now));
            }

            if (!list.isEmpty()) {
                sensorValueRepository.saveAll(list);
                System.out.println("Saved sensor values: " + list.size());
            } else {
                System.out.println("No valid sensors to save");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ========================
    // MQTT START
    // ========================
    @PostConstruct
    public void start() {

        new Thread(() -> {
            try {
                MqttClient client = new MqttClient(BROKER, MqttClient.generateClientId());
                client.connect();

                System.out.println("MQTT Connected");

                client.subscribe(TOPIC, (topic, msg) -> {

                    String payload = new String(msg.getPayload());
                    System.out.println("Received: " + payload);

                    try {
                        ObjectMapper mapper = new ObjectMapper();
                        Map<String, Object> data = mapper.readValue(payload, Map.class);

                        // 🚨 CHECK DATA
                        if (isInvalid(data)) {

                            long now = System.currentTimeMillis();

                            if (now - lastBackupCall > 600000) {
                                System.out.println("Invalid data -> call NASA");
                                nasaBackupService.fetchBackupWeatherData("fieldTest");
                                lastBackupCall = now;
                            } else {
                                System.out.println("Skip NASA (cooldown)");
                            }

                            return;
                        }

                        // ✅ DATA OK
                        saveSensorValues(data);

                    } catch (Exception e) {

                        System.out.println("Parse error → call NASA");
                        nasaBackupService.fetchBackupWeatherData("fieldTest");
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    // ========================
    // VALIDATE INPUT DATA
    // ========================
    private boolean isInvalid(Map<String, Object> data) {

        try {
            double temp = parse(data.get("t"));
            double hum = parse(data.get("h"));
            double rain = parse(data.get("rai"));
            double rad = parse(data.get("rad"));
            double wind = parse(data.get("w"));

            if (temp < -10 || temp > 60) return true;
            if (hum < 0 || hum > 100) return true;
            if (rain < 0 || rain > 500) return true;
            if (rad < 0) return true;
            if (wind < 0 || wind > 50) return true;

        } catch (Exception e) {
            return true;
        }

        return false;
    }
}