package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.IrrigationHistory;
import com.example.demo.repositories.mongo.IrrigationHistoryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class IrrigationHistoryService {

    @Autowired
    private IrrigationHistoryRepository irrigationHistoryRepository;

    public List<IrrigationHistory> getByFieldId(String fieldId) {
        return irrigationHistoryRepository.findByFieldIdOrderByTimeDesc(fieldId);
    }

    public IrrigationHistory create(IrrigationHistory history) {
        if (history.getTime() == null || history.getTime().isEmpty()) {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            history.setTime(LocalDateTime.now().format(formatter));
        }
        return irrigationHistoryRepository.save(history);
    }

    public void delete(String id) {
        irrigationHistoryRepository.deleteById(id);
    }

    public void deleteByFieldId(String fieldId) {
        irrigationHistoryRepository.deleteByFieldId(fieldId);
    }
}
