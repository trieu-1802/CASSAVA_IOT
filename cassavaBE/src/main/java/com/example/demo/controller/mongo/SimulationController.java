package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.entity.MongoEntity.SimulationResult;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import com.example.demo.service.Mongo.FieldSimulator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/simulation")
public class SimulationController {

    @Autowired
    private FieldSimulator fieldSimulator;

    @Autowired
    private FieldMongoRepository fieldRepository;

    // =========================
    // TEST WITH DB FIELD
    // =========================
    @GetMapping("/fieldTest")
    public SimulationResult testWithDbField() {

        Field field = fieldRepository
                .findById("fieldTest")
                .orElseThrow(() -> new RuntimeException("Field not found"));

        return fieldSimulator.simulate(field);
    }
}