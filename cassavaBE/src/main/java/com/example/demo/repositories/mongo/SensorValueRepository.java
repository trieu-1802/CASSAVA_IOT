package com.example.demo.repositories.mongo;

import com.example.demo.entity.MongoEntity.SensorValue;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Date;
import java.util.List;

public interface SensorValueRepository extends MongoRepository<SensorValue, String> {

    List<SensorValue> findByFieldIdAndSensorId(String fieldId, String sensorId);

    List<SensorValue> findTop1ByFieldIdAndSensorIdOrderByTimeDesc(String fieldId, String sensorId);

    List<SensorValue> findBySensorIdAndTimeBetweenOrderByTimeAsc(
        String sensorId,
        Date start,
        Date end
    );
}