package com.example.demo.entity;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChartData {
    private String day;             // Cột 'day'
    private double actualTranspiration; // Cột 'actualTranspiration'
    private double evaporation;
    private double et0;
    private double thetaEquiv;
    public ChartData(String day, double thetaEquiv) {
        this.day = day;

        this.thetaEquiv = thetaEquiv;
    }

    public ChartData() {}



}