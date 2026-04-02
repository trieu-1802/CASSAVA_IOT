package com.example.demo.entity;

import java.util.List;

public class ImageInformation {
    List<String> listImage;
    Boolean isSick;
    Double Chlorofyll;

    public List<String> getListImage() {
        return listImage;
    }

    public ImageInformation() {
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
        return Chlorofyll;
    }

    public void setChlorofyll(Double chlorofyll) {
        Chlorofyll = chlorofyll;
    }
}
