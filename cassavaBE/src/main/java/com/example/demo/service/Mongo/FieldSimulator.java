package com.example.demo.service.Mongo;

import com.example.demo.entity.Field;
import com.example.demo.entity.MongoEntity.FieldSimulationResult;
import com.example.demo.repositories.mongo.FieldSimulationResultRepository;
import com.example.demo.service.Mongo.SensorValueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
public class FieldSimulator {

    @Autowired
    private SensorValueService sensorValueService;

    @Autowired
    private FieldSimulationResultRepository simulationResultRepository;

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

        // 5. Lưu kết quả vào MongoDB
        List<FieldSimulationResult> savedResults = saveResultsToMongo(fieldId, field);

        // 6. Trả về kết quả
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "Mô phỏng hoàn tất cho field: " + fieldId);
        result.put("dataPoints", combinedData.size());
        result.put("simulationResults", savedResults.size());

        return result;
    }

    private List<FieldSimulationResult> saveResultsToMongo(String fieldId, Field field) {
        List<List<Double>> results = field._results;

        if (results == null || results.isEmpty() || results.get(0).isEmpty()) {
            return Collections.emptyList();
        }

        simulationResultRepository.deleteByFieldId(fieldId);

        List<FieldSimulationResult> toSave = new ArrayList<>();

        for (int i = 1; i < results.get(0).size(); i++) {
            // Convert DOY to Date (same logic as writeDataCsvNew)
            int doy = (int) Math.ceil(results.get(8).get(i));
            int year = 2023 + (doy - 1) / 365;
            int dayOfYear = (doy - 1) % 365 + 1;
            LocalDate localDate = LocalDate.ofYearDay(year, dayOfYear);
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
