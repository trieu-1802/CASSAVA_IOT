package com.example.demo.service.Mongo;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.demo.repositories.mongo.FieldSensorRepository;
import com.example.demo.repositories.mongo.SensorValueRepository;
import com.example.demo.entity.MongoEntity.FieldSensor;
import com.example.demo.entity.MongoEntity.SensorValue;

import java.util.Map;
import java.util.TreeMap;
import java.util.HashMap;
import java.util.Date;
import java.util.Calendar;
import java.util.List;
import java.util.ArrayList;

@Service
public class MongoWeatherProvider implements WeatherProvider {

    @Autowired
    private SensorValueRepository sensorRepo;

    @Autowired
    private FieldSensorRepository fieldSensorRepo;

    // 🔥 cache toàn bộ data
    private Map<String, TreeMap<Date, Double>> sensorData = new HashMap<>();

    private Date startTime;

    @Override
    public void loadData(String fieldId, Date start, Date end) {

        this.startTime = start;

        sensorData.clear();

        List<FieldSensor> sensors = fieldSensorRepo.findByFieldId(fieldId);

        for (FieldSensor fs : sensors) {

            List<SensorValue> values =
                    sensorRepo.findBySensorIdAndTimeBetweenOrderByTimeAsc(
                            fs.getId(), start, end
                    );

            TreeMap<Date, Double> series = new TreeMap<>();

            for (SensorValue v : values) {
                series.put(v.getTime(), v.getValue());
            }

            sensorData.put(fs.getSensorId(), series);
        }
    }

    @Override
    public List<Double> getWeather(double t) {

        Date time = new Date(startTime.getTime() + (long)(t * 86400000));

        Calendar cal = Calendar.getInstance();
        cal.setTime(time);
        double doy = cal.get(Calendar.DAY_OF_YEAR);

        double wind = getValue("wind", time);
        double rain = getValue("rain", time);
        double temp = getValue("temperature", time);
        double humidity = getValue("humidity", time);
        double radiation = getValue("radiation", time);

        double ppfd = radiation * 2.15;

        double et0 = hourlyET(
                temp,
                radiation,
                humidity,
                wind,
                doy,
                10.0,
                106.0,
                10.0,
                105.0,
                2.0
        );

     /**   return new ArrayList<>(List.of(
                0.0,        // time (dummy)
                doy,        // DOY
                ppfd,       // radiation
                temp,       // temp
                rain,       // rain
                humidity,   // RH
                wind        // wind
        ));
      */
        // FIX: Trả về đúng thứ tự wd mà rk4Step trong Field.java đang mong đợi
        double dt = 1.0 / 24.0; // Giả định dt là 1 giờ (có thể điều chỉnh tùy logic của bạn)
        return new ArrayList<>(List.of(
                rain,       // wd[0]: Mưa
                temp,       // wd[1]: Nhiệt độ
                ppfd,       // wd[2]: Bức xạ quang hợp
                et0,        // wd[3]: Độ bốc hơi nước
                0.0,        // wd[4]: Cột tưới (mặc định 0)
                dt,         // wd[5]: Bước thời gian dt
                0.0         // wd[6]: Row Index (Có thể để 0 vì không đọc từ CSV nữa)
        ));
    }

    // =========================
    // nội suy đơn giản
    // =========================
    private double getValue(String type, Date time) {

        TreeMap<Date, Double> series = sensorData.get(type);

        if (series == null || series.isEmpty()) return 0;

        Map.Entry<Date, Double> entry = series.floorEntry(time);

        return entry != null ? entry.getValue() : series.firstEntry().getValue();
    }

    public double hourlyET(
            final double tempC,
            final double radiation,
            final double relativeHumidity,
            final double wind,
            final double doy,
            final double latitude,
            final double longitude,
            final double elevation,
            final double longZ,
            final double height) {

        final double pi = Math.PI;
        final double hours = (doy % 1) * 24;
        final double tempK = tempC + 273.16;

        final double Rs = radiation * 3600 / 1e+06;
        final double P = 101.3 *
                Math.pow((293 - 0.0065 * elevation) / 293, 5.256);
        final double psi = 0.000665 * P;

        final double Delta = 2503 *
                Math.exp((17.27 * tempC) / (tempC + 237.3)) /
                Math.pow(tempC + 237.3, 2);
        final double eaSat = 0.61078 *
                Math.exp((17.269 * tempC) / (tempC + 237.3));
        final double ea = (relativeHumidity / 100) * eaSat;

        final double DPV = eaSat - ea;
        final double dr = 1 + 0.033 * Math.cos(2 * pi * doy / 365.0);
        final double delta = 0.409 *
                Math.sin(2 * pi * doy / 365.0 - 1.39);
        final double phi = latitude * (pi / 180);
        final double b = 2.0 * pi * (doy - 81.0) / 364.0;

        final double Sc = 0.1645 * Math.sin(2 * b) - 0.1255 * Math.cos(b) - 0.025 * Math.sin(b);
        final double hourAngle = (pi / 12) *
                ((hours + 0.06667 * (longitude * pi / 180.0 - longZ * pi / 180.0) + Sc) - 12.0);
        final double w1 = hourAngle - ((pi) / 24);
        final double w2 = hourAngle + ((pi) / 24);
        final double hourAngleS = Math.acos(-Math.tan(phi) * Math.tan(delta));
        final double w1c = (w1 < -hourAngleS) ? -hourAngleS : (w1 > hourAngleS) ? hourAngleS : (w1 > w2) ? w2 : w1;
        final double w2c = (w2 < -hourAngleS) ? -hourAngleS : (w2 > hourAngleS) ? hourAngleS : w2;

        final double Beta = Math.asin((Math.sin(phi) * Math.sin(delta) + Math.cos(phi) * Math.cos(delta) * Math.cos(hourAngle)));

        final double Ra = (Beta <= 0) ? 1e-45 : ((12 / pi) * 4.92 * dr) *
                (((w2c - w1c) * Math.sin(phi) * Math.sin(delta)) +
                        (Math.cos(phi) * Math.cos(delta) * (Math.sin(w2) - Math.sin(w1))));

        final double Rso = (0.75 + 2e-05 * elevation) * Ra;

        final double RsRso = (Rs / Rso <= 0.3) ? 0.0 : (Rs / Rso >= 1) ? 1.0 : Rs / Rso;
        final double fcd = (1.35 * RsRso - 0.35 <= 0.05) ? 0.05 : (1.35 * RsRso - 0.35 < 1) ? 1.35 * RsRso - 0.35 : 1;

        final double Rna = ((1 - 0.23) * Rs) -
                (2.042e-10 * fcd * (0.34 - 0.14 * Math.sqrt(ea)) * Math.pow(tempK, 4));

        final double Ghr = (Rna > 0) ? 0.04 : 0.2;
        // G for hourly depend on Rna (or Rn in EThourly)
        final double Gday = Rna * Ghr;
        final double wind2 = wind * (4.87 / (Math.log(67.8 * height - 5.42)));
        final double windf = (radiation > 1e-6) ? 0.25 : 1.7;

        final double EThourly = ((0.408 * Delta * (Rna - Gday)) +
                (psi * (66 / tempK) * wind2 * (DPV))) /
                (Delta + (psi * (1 + (windf * wind2))));

        return EThourly;
    }

}