package com.example.demo.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.NoArgsConstructor;
public class WeatherRequest{
    @JsonProperty("Doy")
    public String doy;

    @JsonProperty("Rain")
    public String rain;

    @JsonProperty("dt")
    public String dt;

    @JsonProperty("Temp")
    public String temp;

    @JsonProperty("Radiation")
    public String radiation;

    @JsonProperty("Relative Humidity")
    public String relativeHumidity;

    @JsonProperty("Wind")
    public String wind;

    @JsonProperty("lat")
    public String lat;

    @JsonProperty("long")
    public String lon;

    @JsonProperty("elev")
    public String elev;

    @JsonProperty("height")
    public String height;

    @JsonProperty("irr\r")
    public String irr;

    public String getDoy() {
        return doy;
    }

    public String getRain() {
        return rain;
    }

    public String getDt() {
        return dt;
    }

    public String getTemp() {
        return temp;
    }

    public String getRadiation() {
        return radiation;
    }

    public String getRelativeHumidity() {
        return relativeHumidity;
    }

    public String getWind() {
        return wind;
    }

    public String getLat() {
        return lat;
    }

    public String getLon() {
        return lon;
    }

    public String getElev() {
        return elev;
    }

    public String getHeight() {
        return height;
    }

    public String getIrr() {
        return irr;
    }

    public void setDoy(String doy) {
        this.doy = doy;
    }

    public void setRain(String rain) {
        this.rain = rain;
    }

    public void setDt(String dt) {
        this.dt = dt;
    }

    public void setTemp(String temp) {
        this.temp = temp;
    }

    public void setRadiation(String radiation) {
        this.radiation = radiation;
    }

    public void setRelativeHumidity(String relativeHumidity) {
        this.relativeHumidity = relativeHumidity;
    }

    public void setWind(String wind) {
        this.wind = wind;
    }

    public void setLat(String lat) {
        this.lat = lat;
    }

    public void setLon(String lon) {
        this.lon = lon;
    }

    public void setElev(String elev) {
        this.elev = elev;
    }

    public void setHeight(String height) {
        this.height = height;
    }

    public void setIrr(String irr) {
        this.irr = irr;
    }
}


