package com.example.demo.entity.MongoEntity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * One row per anomalous reading. The edge C binaries still write the raw
 * value to `sensor_value`; this collection layers a corrected/imputed value
 * on top — downstream consumers (simulator, FE) can JOIN by (time, sensorId)
 * and prefer `predicted` over the raw value.
 */
@Document(collection = "sensor_correction")
public class SensorCorrection {

    @Id
    private String id;

    private Date time;
    private String groupId;
    private String fieldId;     // nullable — soil sensors are field-scoped; weather is group-scoped
    private String sensorId;

    private Double actual;      // raw value received over MQTT
    private Double predicted;   // imputation suggested by the preferred detector
    private String method;      // detector name (e.g. seasonal_zscore, sarima_residual)
    private Double score;       // |z|-like score from that detector

    public SensorCorrection() {}

    public String getId() { return id; }

    public Date getTime() { return time; }
    public void setTime(Date time) { this.time = time; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getFieldId() { return fieldId; }
    public void setFieldId(String fieldId) { this.fieldId = fieldId; }

    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }

    public Double getActual() { return actual; }
    public void setActual(Double actual) { this.actual = actual; }

    public Double getPredicted() { return predicted; }
    public void setPredicted(Double predicted) { this.predicted = predicted; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
}
