package com.example.demo.entity.MongoEntity;

public class IrrigationInformation {

    private String time;
    private double amount;
    private double duration;

    public IrrigationInformation() {
    }

    public IrrigationInformation(String time, double amount, double duration) {
        this.time = time;
        this.amount = amount;
        this.duration = duration;
    }

    // getters/setters

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public double getDuration() {
        return duration;
    }

    public void setDuration(double duration) {
        this.duration = duration;
    }
}