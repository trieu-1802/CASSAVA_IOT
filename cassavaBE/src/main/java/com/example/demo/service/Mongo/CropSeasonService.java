package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CropSeasonService {

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private FieldMongoRepository fieldRepository;

    /**
     * Liệt kê các mùa vụ của một cánh đồng theo thứ tự giảm dần (mới nhất trước).
     * Mùa được xác định bằng cropStartTime — lấy từ cả simulation_result và
     * irrigation_history, hợp nhất theo ngày. Vụ đang chạy (= field.startTime hiện tại)
     * được đánh dấu isCurrent=true; cropEndTime của mỗi vụ cũ được suy từ cropStartTime
     * của vụ kế tiếp (hoặc field.endTime cho vụ đang chạy).
     */
    public List<Map<String, Object>> listSeasons(String fieldId) {
        Field field = fieldRepository.findById(fieldId).orElse(null);
        if (field == null) return Collections.emptyList();

        Map<Date, long[]> counts = new TreeMap<>(Comparator.reverseOrder());

        accumulate(counts, aggregateCounts("simulation_result", fieldId), 0);
        accumulate(counts, aggregateCounts("irrigation_history", fieldId), 1);

        // Đảm bảo vụ đang chạy luôn hiển thị dù chưa có record nào
        if (field.getStartTime() != null) {
            counts.computeIfAbsent(field.getStartTime(), k -> new long[2]);
        }

        List<Map<String, Object>> seasons = new ArrayList<>(counts.size());
        List<Date> orderedDesc = new ArrayList<>(counts.keySet()); // đã DESC do TreeMap reverseOrder
        for (int i = 0; i < orderedDesc.size(); i++) {
            Date start = orderedDesc.get(i);
            boolean isCurrent = field.getStartTime() != null && start.equals(field.getStartTime());
            Date end;
            if (isCurrent) {
                end = field.getEndTime();
            } else {
                // Với vụ cũ: endTime = cropStartTime của vụ kế tiếp theo thời gian (index i-1 vì DESC)
                end = i > 0 ? orderedDesc.get(i - 1) : null;
            }

            Map<String, Object> season = new LinkedHashMap<>();
            season.put("cropStartTime", start);
            season.put("cropEndTime", end);
            season.put("isCurrent", isCurrent);
            season.put("simulationCount", counts.get(start)[0]);
            season.put("irrigationCount", counts.get(start)[1]);
            seasons.add(season);
        }
        return seasons;
    }

    private Map<Date, Long> aggregateCounts(String collection, String fieldId) {
        Aggregation agg = Aggregation.newAggregation(
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where("fieldId").is(fieldId)),
                Aggregation.match(org.springframework.data.mongodb.core.query.Criteria.where("cropStartTime").ne(null)),
                Aggregation.group("cropStartTime").count().as("count")
        );
        AggregationResults<Map> results = mongoTemplate.aggregate(agg, collection, Map.class);
        Map<Date, Long> out = new HashMap<>();
        for (Map row : results.getMappedResults()) {
            Object id = row.get("_id");
            Object c = row.get("count");
            if (id instanceof Date && c instanceof Number) {
                out.put((Date) id, ((Number) c).longValue());
            }
        }
        return out;
    }

    private void accumulate(Map<Date, long[]> target, Map<Date, Long> counts, int idx) {
        for (Map.Entry<Date, Long> e : counts.entrySet()) {
            target.computeIfAbsent(e.getKey(), k -> new long[2])[idx] = e.getValue();
        }
    }
}
