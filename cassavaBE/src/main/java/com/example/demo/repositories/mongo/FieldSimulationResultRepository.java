package com.example.demo.repositories.mongo;

import java.util.List;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.example.demo.entity.MongoEntity.FieldSimulationResult;


public interface FieldSimulationResultRepository extends MongoRepository<FieldSimulationResult, String> {

    List<FieldSimulationResult> findByFieldIdOrderByTimeAsc(String fieldId);

    void deleteByFieldId(String fieldId);
}