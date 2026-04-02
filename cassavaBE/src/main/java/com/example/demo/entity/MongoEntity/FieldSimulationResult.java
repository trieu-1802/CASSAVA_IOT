package com.example.demo.entity.MongoEntity;


import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "field_simulation_result")
public class FieldSimulationResult {

    @Id
    private String id;

    private String fieldId;

    private Date runTime;        // thời điểm cron chạy

    private Date simStartTime;
    private Date simEndTime;

    private double expectedYield;
    private double totalWater;

    private List<List<Double>> rawResults; // optional
}