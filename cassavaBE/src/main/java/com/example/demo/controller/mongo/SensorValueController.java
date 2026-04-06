package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.SensorValue;
import com.example.demo.service.Mongo.SensorValueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/sensor-values")
public class SensorValueController {
    @Autowired
    private SensorValueService sensorValueService;

    // API: GET http://localhost:8081/api/sensor-values/history?fieldId=fieldTest&sensorId=temperature
    @GetMapping("/history")
    public List<SensorValue> getSensorHistory(
            @RequestParam String fieldId,
            @RequestParam String sensorId) {
       // LocalDateTime twentyFourHoursAgo = LocalDateTime.now().minusHours(24);
        return sensorValueService.getHistory(fieldId, sensorId);
    }
    @GetMapping("/combined")
    public List<String> getCombinedData(@RequestParam String fieldId) {
        // API này sẽ trả về List các String theo định dạng cậu muốn
        return sensorValueService.getCombinedValues(fieldId);
    }
}
