package com.example.demo.controller.mongo;

import com.example.demo.entity.MongoEntity.Field;
import com.example.demo.service.Mongo.FieldMongoService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    @PostMapping("/cloneField")
    public String clone() {
        return "clone sucessfully";
    }
}