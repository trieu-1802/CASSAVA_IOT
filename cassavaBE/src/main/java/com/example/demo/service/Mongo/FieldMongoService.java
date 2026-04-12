package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.repositories.UserRepository;
import com.example.demo.repositories.mongo.FieldMongoRepository;
import com.example.demo.repositories.mongo.FieldSimulationResultRepository;
import com.example.demo.repositories.mongo.IrrigationHistoryRepository;
import com.example.demo.repositories.mongo.SensorValueRepository;
import org.springframework.beans.BeanUtils;
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

    @Autowired
    private SensorValueRepository sensorValueRepository;

    @Autowired
    private FieldSimulationResultRepository simulationResultRepository;

    @Autowired
    private IrrigationHistoryRepository irrigationHistoryRepository;

    // ========================
    // CREATE (FIXED)
    // ========================
    public Field create(Field field) {

        if (field.getIdUser() == null || !userRepository.existsById(field.getIdUser())) {
            throw new RuntimeException("Invalid user");
        }
        if (field.getName() == null || field.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Tên cánh đồng không được để trống");
        }
        if (fieldRepository.existsByName(field.getName())) {
            throw new RuntimeException("Tên cánh đồng '" + field.getName() + "' đã tồn tại trong hệ thống");
        }

        validateField(field);

        field.setId(null);
        field.setStartTime(new Date());

        return fieldRepository.save(field);
    }
    public Field clone (String srcFieldId, String newName) {
        if (newName == null || newName.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên cánh đồng mới không được để trống");
        }
        Field sourceField = fieldRepository.findById(srcFieldId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy cánh đồng gốc ID: " + srcFieldId));

        if (fieldRepository.existsByName(newName)) {
            throw new RuntimeException("Tên cánh đồng '" + newName + "' đã tồn tại trong hệ thống");
        }
        Field newField = new Field();
        BeanUtils.copyProperties(sourceField, newField);

        newField.setId(null);
        newField.setName(newName);
        newField.setStartTime(new Date());
        newField.setIrrigating(false);
        newField.setDAP(1);

        return fieldRepository.save(newField);
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
        old.setName(newData.getName());
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

    // ========================
    // RESET CROP CYCLE ("Mùa mới")
    // Clears per-crop data (sensor values, simulation results, irrigation history)
    // and resets the Field's per-crop state. Keeps field config and sensor mappings.
    // ========================
    public Field resetCropCycle(String id) {
        Field field = fieldRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy cánh đồng ID: " + id));

        sensorValueRepository.deleteByFieldId(id);
        simulationResultRepository.deleteByFieldId(id);
        irrigationHistoryRepository.deleteByFieldId(id);

        field.setStartTime(new Date());
        field.setDAP(1);
        field.setIrrigating(false);

        return fieldRepository.save(field);
    }
}