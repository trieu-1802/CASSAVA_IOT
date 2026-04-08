package com.example.demo.repositories.mongo;

import com.example.demo.entity.MongoEntity.IrrigationHistory;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IrrigationHistoryRepository extends MongoRepository<IrrigationHistory, String> {

    List<IrrigationHistory> findByFieldIdOrderByTimeDesc(String fieldId);

    void deleteByFieldId(String fieldId);
}
