package com.example.demo.entity.MongoEntity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "field")
public class Field {

    @Id
    private String id;

    private String idUser;

    private double acreage = 1000;

    private boolean autoIrrigation = true;

    private double fieldCapacity = 0.8;

    private int irrigationDuration = 30;

    private double distanceBetweenRow = 1.2;

    private double distanceBetweenHole = 0.3;

    private double dripRate = 2;

    private double scaleRain = 0.7;

    private int numberOfHoles = 100;

    private double fertilizationLevel = 1;

    private int dAP = 10;

    // 🔥 luôn set khi tạo object
    private Date startTime = new Date();

    private boolean isIrrigating = false;

    // ===== GETTERS / SETTERS =====

    public String getId() { return id; }

    public String getIdUser() { return idUser; }
    public void setIdUser(String idUser) { this.idUser = idUser; }

    public double getAcreage() { return acreage; }
    public void setAcreage(double acreage) { this.acreage = acreage; }

    public boolean isAutoIrrigation() { return autoIrrigation; }
    public void setAutoIrrigation(boolean autoIrrigation) { this.autoIrrigation = autoIrrigation; }

    public double getFieldCapacity() { return fieldCapacity; }
    public void setFieldCapacity(double fieldCapacity) { this.fieldCapacity = fieldCapacity; }

    public int getIrrigationDuration() { return irrigationDuration; }
    public void setIrrigationDuration(int irrigationDuration) { this.irrigationDuration = irrigationDuration; }

    public double getDistanceBetweenRow() { return distanceBetweenRow; }
    public void setDistanceBetweenRow(double distanceBetweenRow) { this.distanceBetweenRow = distanceBetweenRow; }

    public double getDistanceBetweenHole() { return distanceBetweenHole; }
    public void setDistanceBetweenHole(double distanceBetweenHole) { this.distanceBetweenHole = distanceBetweenHole; }

    public double getDripRate() { return dripRate; }
    public void setDripRate(double dripRate) { this.dripRate = dripRate; }

    public double getScaleRain() { return scaleRain; }
    public void setScaleRain(double scaleRain) { this.scaleRain = scaleRain; }

    public int getNumberOfHoles() { return numberOfHoles; }
    public void setNumberOfHoles(int numberOfHoles) { this.numberOfHoles = numberOfHoles; }

    public double getFertilizationLevel() { return fertilizationLevel; }
    public void setFertilizationLevel(double fertilizationLevel) { this.fertilizationLevel = fertilizationLevel; }

    public int getDAP() { return dAP; }
    public void setDAP(int dAP) { this.dAP = dAP; }

    public Date getStartTime() { return startTime; }
    public void setStartTime(Date startTime) { this.startTime = startTime; }

    public boolean isIrrigating() { return isIrrigating; }
    public void setIrrigating(boolean irrigating) { isIrrigating = irrigating; }
}