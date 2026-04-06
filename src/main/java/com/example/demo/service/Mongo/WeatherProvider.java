package com.example.demo.service.Mongo;

import java.util.Date;
import java.util.List;

public interface WeatherProvider {

    void loadData(String fieldId, Date start, Date end);

    List<Double> getWeather(double t);
}
