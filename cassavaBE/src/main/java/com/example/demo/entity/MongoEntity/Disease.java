package com.example.demo.entity.MongoEntity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "diseases")
public class Disease {

    @Id
    private String id;

    private String fieldId;

    private String urlImage;
    private String disease;
    private boolean isSick;
    private String time;

    public Disease() {
    }

    public Disease(String fieldId, String disease, boolean isSick,
                   String time, String urlImage) {
        this.fieldId = fieldId;
        this.disease = disease;
        this.isSick = isSick;
        this.time = time;
        this.urlImage = urlImage;
    }

    // getters/setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFieldId() {
        return fieldId;
    }

    public void setFieldId(String fieldId) {
        this.fieldId = fieldId;
    }

    public String getUrlImage() {
        return urlImage;
    }

    public void setUrlImage(String urlImage) {
        this.urlImage = urlImage;
    }

    public String getDisease() {
        return disease;
    }

    public void setDisease(String disease) {
        this.disease = disease;
    }

    public boolean isSick() {
        return isSick;
    }

    public void setSick(boolean sick) {
        isSick = sick;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}