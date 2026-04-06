package com.example.demo.entity.MongoEntity;

import java.util.List;

public class ImageInformation {

    private List<String> listImage;
    private Boolean isSick;
    private Double chlorofyll;

    public ImageInformation() {
    }

    public List<String> getListImage() {
        return listImage;
    }

    public void setListImage(List<String> listImage) {
        this.listImage = listImage;
    }

    public Boolean getSick() {
        return isSick;
    }

    public void setSick(Boolean sick) {
        isSick = sick;
    }

    public Double getChlorofyll() {
        return chlorofyll;
    }

    public void setChlorofyll(Double chlorofyll) {
        this.chlorofyll = chlorofyll;
    }
}