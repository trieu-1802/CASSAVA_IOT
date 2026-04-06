package com.example.demo.controller;

import com.example.demo.service.NasaBackupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.util.Date;

@RestController
@RequestMapping("/api/nasa")
public class NasaController {

    @Autowired
    private NasaBackupService nasaBackupService;

    // ==================================================
    // 👉 GỌI NASA THEO KHOẢNG THỜI GIAN
    // ==================================================
    @PostMapping("/fetch")
    public String fetchFromNasaManual(
            @RequestParam String fieldName,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date from,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date to) {

        nasaBackupService.fetchFromNasa(fieldName, from, to);

        return "✅ NASA data fetched & saved";
    }

    // ==================================================
    // 👉 TEST BACKUP FLOW (NASA → fallback OpenWeather)
    // ==================================================
    @GetMapping("/backup")
    public String testBackupFlow(@RequestParam String fieldName) {

        nasaBackupService.fetchBackupWeatherData(fieldName);

        return "⚠️ Backup flow triggered (NASA/OpenWeather)";
    }

    // ==================================================
    // 👉 TEST OPEN WEATHER
    // ==================================================
    @GetMapping("/openweather")
    public String testOpenWeather(@RequestParam(defaultValue = "fieldTest") String fieldName) {

        nasaBackupService.fetchFromOpenWeather(fieldName);

        return "✅ OpenWeather called";
    }
}