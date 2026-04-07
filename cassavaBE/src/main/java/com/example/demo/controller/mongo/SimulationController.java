package com.example.demo.controller.mongo;

import com.example.demo.entity.Field;
import com.example.demo.entity.MongoEntity.FieldSimulationResult;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import com.example.demo.repositories.mongo.FieldSimulationResultRepository;
import com.example.demo.service.Mongo.FieldSimulator;

import com.example.demo.service.Mongo.SensorValueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/simulation")
public class SimulationController {
   @Autowired
   private FieldSimulator fieldSimulator;

   @Autowired
   private FieldSimulationResultRepository simulationResultRepository;

    // API: GET http://localhost:8081/simulation/run?fieldId=fieldTest
    @GetMapping("/run")
    public ResponseEntity<?> runModelSimulation(
            @RequestParam String fieldId
            ) {
        try {
            Map<String, Object> result = fieldSimulator.runSimulation(fieldId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body("Lỗi mô phỏng: " + e.getMessage());
        }
    }

    @GetMapping("/data")
    public ResponseEntity<?> getSimulationData(@RequestParam String fieldId) {

        List<FieldSimulationResult> data =
                simulationResultRepository.findByFieldIdOrderByTimeAsc(fieldId);

        return ResponseEntity.ok(data);
    }
}