package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.SensorValue;

import com.example.demo.repositories.mongo.SensorValueRepository;
import com.mongodb.BasicDBObject;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SensorValueService {
    @Autowired
    private SensorValueRepository repository;
    @Autowired
    private MongoTemplate mongoTemplate;
    public List<SensorValue> getHistory(String fieldId, String sensorId) {
        return repository.findByFieldIdAndSensorIdOrderByTimeDesc(fieldId, sensorId);
    }
    /**
     * gom các dữ liệu thành 1 hàng
     */
    public List<String> getCombinedValues(String fieldId) {
        // 1. Lọc theo fieldId
        MatchOperation matchStage = Aggregation.match(Criteria.where("fieldId").is(fieldId));
        // 2. Gom nhóm theo thời gian (time)
        GroupOperation groupStage = Aggregation.group("time")
                .first("time").as("time")
                // Sử dụng toán tử điều kiện để nhặt giá trị đúng loại sensor
                .push(new BasicDBObject("k", "$sensorId").append("v", "$value")).as("sensors");
        // 3. Sắp xếp thời gian mới nhất lên đầu
        //  SortOperation sortStage = Aggregation.sort(org.springframework.data.domain.Sort.Direction.DESC, "time");
        // sort from old to new
        SortOperation sortStage = Aggregation.sort(org.springframework.data.domain.Sort.Direction.ASC, "time");
        Aggregation aggregation = Aggregation.newAggregation(matchStage, groupStage, sortStage)
                // THÊM DÒNG NÀY ĐỂ FIX LỖI:
                .withOptions(Aggregation.newAggregationOptions().allowDiskUse(true).build());
        AggregationResults<Document> results = mongoTemplate.aggregate(aggregation, "sensor_value", org.bson.Document.class);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return results.getMappedResults().stream().map(doc -> {
         //    java.util.Date timeDate = doc.getDate("time");
            // Chuyển Date sang String định dạng yêu cầu
           // String timeStr = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(timeDate);
            // Cách xử lý KHÔNG cộng thêm 7 giờ
            java.util.Date timeDate = doc.getDate("time");
            // Sử dụng SimpleDateFormat và SET TIMEZONE là UTC để nó không tự cộng 7
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));

            String timeStr = sdf.format(timeDate);
            // Lấy list sensors đã push vào
            List<org.bson.Document> sensors = (List<org.bson.Document>) doc.get("sensors");

            // Tìm giá trị tương ứng từng loại (nếu thiếu thì để 0.0)
            double rad = getValue(sensors, "radiation");
            double temp = getValue(sensors, "temperature");
            double rain = getValue(sensors, "rainfall");
            double hum = getValue(sensors, "humidity");
            double wind = getValue(sensors, "wind");

            // Trả về đúng định dạng chuỗi Kiên yêu cầu
            return String.format("%s,%s,%f,%f,%f,%f,%f",
                    timeStr, timeStr, rad, temp, rain, hum, wind);
        }).collect(Collectors.toList());
    }

    private double getValue(List<org.bson.Document> sensors, String type) {
    return sensors.stream()
            .filter(s -> s.getString("k").equals(type))
            .map(s -> s.get("v", Number.class).doubleValue())
            .findFirst().orElse(0.0);
    }
}