package com.example.demo.entity;

public class Disease {
    String urlImage; // link ảnh lên storage
    String disease; // ten bệnh
    boolean isSick; // có bị bệnh hay không
    String time; // thời gian chẩn đoán

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

    public boolean getSick() {
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

    public Disease() {
    }
}
