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
        if (!field.autoIrrigation || field.listHistory.isEmpty()) {
            return Collections.emptyList();
        }

        irrigationHistoryRepository.deleteByFieldId(fieldId);

        List<IrrigationHistory> toSave = new ArrayList<>();

        // Use exact irrigation events recorded during simulation (Field.java line 794-815)
        for (com.example.demo.entity.HistoryIrrigation h : field.listHistory) {
            double amountM3Ha = h.getAmount() * 10.0; // convert mm to m³/ha
            double duration = h.getDuration(); // already in minutes

            // Field.java stores time with hardcoded 2024 year, but dayOfYear and hour:minute are correct
            // Re-derive correct date using anchor-based approach
            LocalDate originalDate = LocalDate.parse(h.getTime().substring(0, 10));
            int doy = originalDate.getDayOfYear();
            LocalDate correctedDate = baseDate.plusDays(doy - baseDoy);

            String correctedTime = correctedDate + " " + h.getTime().substring(11);

            toSave.add(new IrrigationHistory(fieldId, correctedTime, h.getUserName(), amountM3Ha, duration));
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
            double irrigation = results.get(2).get(i) * 10.0; // convert mm to m³/ha
            double leafArea = results.get(3).get(i);
            double labileCarbon = results.get(4).get(i);

            toSave.add(new FieldSimulationResult(fieldId, time, yield, irrigation, leafArea, labileCarbon));
        }

        return simulationResultRepository.saveAll(toSave);
    }
}
