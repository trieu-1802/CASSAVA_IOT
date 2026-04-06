package com.example.demo.entity.MongoEntity;

import java.util.List;

public class SimulationResult {

    private double expectedYield;
    private double totalWater;
    private List<List<Double>> rawResults;

    public double getExpectedYield() { return expectedYield; }
    public void setExpectedYield(double expectedYield) { this.expectedYield = expectedYield; }

    public double getTotalWater() { return totalWater; }
    public void setTotalWater(double totalWater) { this.totalWater = totalWater; }

    public List<List<Double>> getRawResults() { return rawResults; }
    public void setRawResults(List<List<Double>> rawResults) { this.rawResults = rawResults; }
}