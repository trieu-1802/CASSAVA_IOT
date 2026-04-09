package com.example.demo.service.Mongo;

import com.example.demo.entity.Field;
import com.example.demo.entity.MongoEntity.FieldSimulationResult;
import com.example.demo.entity.MongoEntity.IrrigationHistory;
import com.example.demo.repositories.mongo.FieldSimulationResultRepository;
import com.example.demo.repositories.mongo.IrrigationHistoryRepository;
import com.example.demo.service.Mongo.SensorValueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class FieldSimulator {

    @Autowired
    private SensorValueService sensorValueService;

    @Autowired
    private FieldSimulationResultRepository simulationResultRepository;

    @Autowired
    private IrrigationHistoryRepository irrigationHistoryRepository;

    public Map<String, Object> runSimulation(String fieldId) throws IOException {
        // 1. Lấy dữ liệu từ MongoDB
        List<String> combinedData = sensorValueService.getCombinedValues(fieldId);

        if (combinedData == null || combinedData.isEmpty()) {
            throw new RuntimeException("Không có dữ liệu cảm biến cho cánh đồng này");
        }

        // 2. Khởi tạo đối tượng Field
        Field field = new Field("field simulation");

        // 3. Nạp dữ liệu vào Model
        field.loadAllWeatherDataFromMongo(combinedData);

        // 4. Chạy mô phỏng
        field.runModel();

        // 5. Anchor: first weather data entry's date and DOY
        String firstTimeStr = Field._weatherData.get(0).get(0).toString(); // "yyyy-MM-dd HH:mm:ss"
        LocalDate baseDate = LocalDate.parse(firstTimeStr.substring(0, 10));
        int baseDoy = (int) Math.floor(Double.parseDouble(Field._weatherData.get(0).get(1).toString()));

        // 6. Lưu kết quả vào MongoDB
        List<FieldSimulationResult> savedResults = saveResultsToMongo(fieldId, field, baseDate, baseDoy);

        // 7. Lưu lịch sử tưới nếu autoIrrigation
        List<IrrigationHistory> irrigationRecords = saveIrrigationHistory(fieldId, field, baseDate, baseDoy);

        // 8. Trả về kết quả
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "Mô phỏng hoàn tất cho field: " + fieldId);
        result.put("dataPoints", combinedData.size());
        result.put("simulationResults", savedResults.size());
        result.put("irrigationRecords", irrigationRecords.size());

        return result;
    }

    private List<IrrigationHistory> saveIrrigationHistory(String fieldId, Field field, LocalDate baseDate, int baseDoy) {
        if (!field.autoIrrigation) {
            return Collections.emptyList();
        }

        List<List<Double>> results = field._results;
        if (results == null || results.get(2).isEmpty()) {
            return Collections.emptyList();
        }

        // Clear old irrigation history for this field
        irrigationHistoryRepository.deleteByFieldId(fieldId);

        List<IrrigationHistory> toSave = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        // Iterate each day, compute daily irrigation as difference of cumulative values
        for (int i = 1; i < results.get(2).size(); i++) {
            double irr = results.get(2).get(i) - results.get(2).get(i - 1);

            if (irr <= 0) {
                continue;
            }

            double duration = irr * field.acreage / (field.dripRate * field.numberOfHoles) * 3600; // seconds

            int doy = (int) Math.floor(results.get(8).get(i));
            LocalDate localDate = baseDate.plusDays(doy - baseDoy);
            LocalDateTime dateTime = localDate.atTime(8, 0, 0); // irrigation at 8:00
            String formattedTime = dateTime.format(formatter);

            toSave.add(new IrrigationHistory(fieldId, formattedTime, "admin", irr, duration));
        }

        if (toSave.isEmpty()) {
            return Collections.emptyList();
        }

        return irrigationHistoryRepository.saveAll(toSave);
    }

    private List<FieldSimulationResult> saveResultsToMongo(String fieldId, Field field, LocalDate baseDate, int baseDoy) {
        List<List<Double>> results = field._results;

        if (results == null || results.isEmpty() || results.get(0).isEmpty()) {
            return Collections.emptyList();
        }

        simulationResultRepository.deleteByFieldId(fieldId);

        List<FieldSimulationResult> toSave = new ArrayList<>();

        for (int i = 1; i < results.get(0).size(); i++) {
            int doy = (int) Math.floor(results.get(8).get(i));
            LocalDate localDate = baseDate.plusDays(doy - baseDoy);
            Date time = Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());

            double yield = results.get(0).get(i);
            double irrigation = results.get(2).get(i);
            double leafArea = results.get(3).get(i);
            double labileCarbon = results.get(4).get(i);

            toSave.add(new FieldSimulationResult(fieldId, time, yield, irrigation, leafArea, labileCarbon));
        }

        return simulationResultRepository.saveAll(toSave);
    }
}
