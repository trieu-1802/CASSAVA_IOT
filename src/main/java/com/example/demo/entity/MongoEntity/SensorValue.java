package com.example.demo.entity.MongoEntity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "sensor_value")
public class SensorValue {

    @Id
    private String id;

    private String fieldId;

    private String sensorId; // temperature, humidity, rain...

    private double value;

    private Date time;

    public SensorValue() {}

    public SensorValue(String fieldId, String sensorId, double value, Date time) {
        this.fieldId = fieldId;
        this.sensorId = sensorId;
        this.value = value;
        this.time = time;
    }

    public String getId() {
        return id;
    }

    public String getFieldId() {
        return fieldId;
    }

    public void setFieldId(String fieldId) {
        this.fieldId = fieldId;
    }

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public Date getTime() {
        return time;
    }

    public void setTime(Date time) {
        this.time = time;
    }
}