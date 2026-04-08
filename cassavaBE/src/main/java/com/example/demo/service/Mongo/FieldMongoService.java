package com.example.demo.service.Mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.repositories.UserRepository;
import com.example.demo.repositories.mongo.FieldMongoRepository;
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
    public Field clone (String srcField, String newFieldId) {
        // 1. Kiểm tra ID mới có trống không
        if (newFieldId == null || newFieldId.trim().isEmpty()) {
            throw new IllegalArgumentException("Tên cánh đồng mới không được để trống");
        }
        // 2. Tìm cánh đồng gốc
        Field sourceField = fieldRepository.findById(srcField)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy cánh đồng gốc ID: " + srcField));

        // 3. Kiểm tra xem tên mới đã tồn tại chưa
        if (fieldRepository.existsById(newFieldId)) {
            throw new RuntimeException("Tên cánh đồng '" + newFieldId + "' đã tồn tại trong hệ thống");
        }
        Field newField = new Field();

        // Sử dụng BeanUtils để copy toàn bộ thông số kỹ thuật (acreage, capacity, rate,...)
        BeanUtils.copyProperties(sourceField, newField);

        // 5. Ghi đè các giá trị đặc thù cho bản clone
        newField.setId(newFieldId);           // Đặt tên mới
        newField.setStartTime(new Date());    // Thời gian bắt đầu là lúc bấm clone
        newField.setIrrigating(false);      // Trạng thái tưới mặc định là false
        newField.setDAP(1);                   // Reset DAP về 1 cho cánh đồng mới

        // Lưu ý: idUser sẽ được giữ nguyên từ sourceField (vì copyProperties)
        // Nếu bạn muốn dùng idUser mặc định cứng, hãy set lại ở đây:
        // newField.setIdUser("69ccc364a1e7905cc9356ce3");

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