package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.service.Mongo.FieldMongoService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/mongo/field")
public class FieldMongoController {

    @Autowired
    private FieldMongoService fieldService;

    // ========================
    // CREATE
    // ========================
    @PostMapping("/createField")
    public Field create(@RequestBody Field field) {
        return fieldService.create(field);
    }


    // ========================
    // GET ALL
    // ========================
    @GetMapping
    public List<Field> getAll() {
        return fieldService.getAll();
    }

    // ========================
    // GET BY ID
    // ========================
    @GetMapping("/{id}")
    public Field getById(@PathVariable String id) {
        return fieldService.getById(id);
    }

    // ========================
    // GET BY USER
    // ========================
    @GetMapping("/user/{userId}")
    public List<Field> getByUser(@PathVariable String userId) {
        return fieldService.getByUser(userId);
    }

    // ========================
    // UPDATE
    // ========================
    @PutMapping("/updateField/{id}")
    public Field update(@PathVariable String id, @RequestBody Field field) {
        return fieldService.update(id, field);
    }

    // ========================
    // DELETE
    // ========================
    @DeleteMapping("/{id}")
    public String delete(@PathVariable String id) {
        fieldService.delete(id);
        return "Deleted successfully";
    }
    // ========================
    // RESET CROP CYCLE ("Mùa mới")
    // ========================
    @PostMapping("/resetCrop/{id}")
    public ResponseEntity<?> resetCrop(@PathVariable String id) {
        try {
            Field updated = fieldService.resetCropCycle(id);
            return ResponseEntity.ok(updated);
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Lỗi hệ thống");
        }
    }

    @PostMapping("/clone/{id}")
    public ResponseEntity<?> clone(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            String newName = body.get("newName");
            Field clonedField = fieldService.clone(id, newName);
            return ResponseEntity.status(HttpStatus.CREATED).body(clonedField);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Lỗi hệ thống");
        }
    }
}