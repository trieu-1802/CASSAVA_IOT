package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.repositories.UserRepository;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class FieldMongoService {

    @Autowired
    private FieldMongoRepository fieldRepository;

    @Autowired
    private UserRepository userRepository;

    // ========================
    // CREATE (FIXED)
    // ========================
    public Field create(Field field) {

        // 🔥 validate user
        if (field.getIdUser() == null || !userRepository.existsById(field.getIdUser())) {
            throw new RuntimeException("Invalid user");
        }

        validateField(field);

        field.setStartTime(new Date());
        
        Field saved = fieldRepository.save(field);

        return saved;
    }

    private void validateField(Field field) {

        if (field.getAcreage() <= 0) {
            throw new RuntimeException("Acreage must be > 0");
        }

        if (field.getFieldCapacity() <= 0 || field.getFieldCapacity() > 1) {
            throw new RuntimeException("FieldCapacity must be between 0 and 1");
        }

        if (field.getDripRate() <= 0) {
            throw new RuntimeException("DripRate must be > 0");
        }

        if (field.getNumberOfHoles() <= 0) {
            throw new RuntimeException("NumberOfHoles must be > 0");
        }

        if (field.getScaleRain() < 0) {
            throw new RuntimeException("ScaleRain must be >= 0");
        }
    }

    // ========================
    // GET BY ID
    // ========================
    public Field getById(String id) {
        return fieldRepository.findById(id).orElse(null);
    }

    // ========================
    // GET ALL
    // ========================
    public List<Field> getAll() {
        return fieldRepository.findAll();
    }

    // ========================
    // GET BY USER
    // ========================
    public List<Field> getByUser(String userId) {
        return fieldRepository.findByIdUser(userId);
    }

    // ========================
    // UPDATE
    // ========================
    public Field update(String id, Field newData) {

        Field old = getById(id);
        if (old == null) return null;

        // 🔥 nếu đổi user → validate lại
        if (!userRepository.existsById(newData.getIdUser())) {
            throw new RuntimeException("User not found");
        }

        old.setIdUser(newData.getIdUser());
        old.setAcreage(newData.getAcreage());
        old.setAutoIrrigation(newData.isAutoIrrigation());

        old.setFieldCapacity(newData.getFieldCapacity());
        old.setIrrigationDuration(newData.getIrrigationDuration());

        old.setDistanceBetweenRow(newData.getDistanceBetweenRow());
        old.setDistanceBetweenHole(newData.getDistanceBetweenHole());

        old.setDripRate(newData.getDripRate());
        old.setScaleRain(newData.getScaleRain());

        old.setNumberOfHoles(newData.getNumberOfHoles());

        old.setFertilizationLevel(newData.getFertilizationLevel());
        old.setDAP(newData.getDAP());

        old.setStartTime(newData.getStartTime());

        old.setIrrigating(newData.isIrrigating());

        return fieldRepository.save(old);
    }

    // ========================
    // DELETE
    // ========================
    public void delete(String id) {
        fieldRepository.deleteById(id);
    }
}