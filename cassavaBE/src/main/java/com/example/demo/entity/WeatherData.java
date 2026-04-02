package com.example.demo.entity;
import com.fasterxml.jackson.annotation.JsonProperty;

public class WeatherData {
    @JsonProperty("Doy")
    private int doy;

    @JsonProperty("Rain")
    private double rain;

    @JsonProperty("dt")
    private String dt;

    @JsonProperty("Temp")
    private double temperature;

    @JsonProperty("Radiation")
    private double radiation;

    @JsonProperty("Relative Humidity")
    private double relativeHumidity;

    @JsonProperty("Wind")
    private double wind;

    @JsonProperty("lat")
    private double latitude;

    @JsonProperty("long")
    private double longitude;

    @JsonProperty("elev")
    private double elevation;

    @JsonProperty("height")
    private double height;

    // Getters and setters (optional)
}
