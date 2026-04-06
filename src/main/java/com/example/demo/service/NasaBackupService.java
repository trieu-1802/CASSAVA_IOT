package com.example.demo.service;

import com.example.demo.entity.MeasuredData;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NasaBackupService {

    private static final String LAT = "21.0003895739763";
    private static final String LON = "105.51771687125039";
    private static final String OPENWEATHER_API_KEY = "d6dab181f11e0f9e8858b4fc343da548";

    // ==================================================
    // dùng MQTT data
    // ==================================================
    public void fetchBackupWeatherData(String fieldName) {

    System.out.println("[Backup] Fetching NASA data for " + fieldName);

    try {
        fetchFromNasa(fieldName, new Date(), new Date());
    } catch (Exception e) {
        System.out.println("[Backup] NASA failed → fallback OpenWeather");
        fetchFromOpenWeather(fieldName);
    }
}

    // ==================================================
    // NASA API
    // ==================================================
    public void fetchFromNasa(String fieldName, Date from, Date to) {

        String start = new java.text.SimpleDateFormat("yyyyMMdd").format(from);
        String end = new java.text.SimpleDateFormat("yyyyMMdd").format(to);

        String url = String.format(
                "https://power.larc.nasa.gov/api/temporal/hourly/point?parameters=T2M,RH2M,WS2M,ALLSKY_SFC_SW_DWN&community=AG&longitude=%s&latitude=%s&start=%s&end=%s&format=JSON",
                LON, LAT, start, end);

        System.out.println("[NASA] Calling API: " + url);

        try {
            JSONObject data = new JSONObject(readUrl(url))
                    .getJSONObject("properties")
                    .getJSONObject("parameter");

            Map<String, Double> t2m = toMap(data.getJSONObject("T2M"));
            Map<String, Double> rh2m = toMap(data.getJSONObject("RH2M"));
            Map<String, Double> ws2m = toMap(data.getJSONObject("WS2M"));
            Map<String, Double> radiation = toMap(data.getJSONObject("ALLSKY_SFC_SW_DWN"));

            DateTimeFormatter inputFmt = DateTimeFormatter.ofPattern("yyyyMMddHH");
            DateTimeFormatter outputFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            for (String key : t2m.keySet()) {

                LocalDateTime time = LocalDateTime.parse(key, inputFmt);
                String formatted = time.format(outputFmt);

                double temp = t2m.getOrDefault(key, -999.0);
                double rh = rh2m.getOrDefault(key, -999.0);
                double wind = ws2m.getOrDefault(key, -999.0);
                double radVal = radiation.getOrDefault(key, 0.0);

                if (radVal < 0 || radVal == -999.0) {
                    radVal = 0.005;
                }

                float radiationWm2 = (float) (radVal * 11.574);

                MeasuredData entry = new MeasuredData(fieldName);
                entry.setTime(formatted);
                entry.setTemperature(temp);
                entry.setRelativeHumidity(rh);
                entry.setWindSpeed(wind);
                entry.setRadiation(radiationWm2);

                uploadToFirebase(fieldName, time, entry);

                System.out.println("[NASA] Saved: " + formatted);
            }

        } catch (Exception e) {
            System.out.println("[NASA] Error → fallback OpenWeather");
            e.printStackTrace();
            fetchFromOpenWeather(fieldName);
        }
    }

    // ==================================================
    // OPEN WEATHER FALLBACK
    // ==================================================
    public void fetchFromOpenWeather(String fieldName) {

        String url = String.format(
                "https://api.openweathermap.org/data/2.5/weather?lat=%s&lon=%s&units=metric&appid=%s",
                LAT, LON, OPENWEATHER_API_KEY);

        try {
            JSONObject json = new JSONObject(readUrl(url));

            double temp = json.getJSONObject("main").optDouble("temp", -999);
            double rh = json.getJSONObject("main").optDouble("humidity", -999);
            double wind = json.getJSONObject("wind").optDouble("speed", -999);

            LocalDateTime now = LocalDateTime.now();
            String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            MeasuredData entry = new MeasuredData(fieldName);
            entry.setTime(formatted);
            entry.setTemperature(temp);
            entry.setRelativeHumidity(rh);
            entry.setWindSpeed(wind);
            entry.setRadiation(0f);

            uploadToFirebase(fieldName, now, entry);

            System.out.println("[OpenWeather] Saved: " + formatted);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void uploadToFirebase(String field, LocalDateTime time, MeasuredData data) {
        String dateKey = time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String hourKey = time.format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("user")
                .child(field).child("measured_data")
                .child(dateKey).child(hourKey);

        ref.setValueAsync(data);
    }

    private String readUrl(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        try (InputStream input = conn.getResponseCode() == 200
                ? conn.getInputStream()
                : conn.getErrorStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            return reader.lines().collect(Collectors.joining());
        }
    }

    private Map<String, Double> toMap(JSONObject json) {
        return json.keySet().stream()
                .collect(Collectors.toMap(Function.identity(), k -> json.optDouble(k, -999)));
    }
}