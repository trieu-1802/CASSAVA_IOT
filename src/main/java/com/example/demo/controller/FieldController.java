package com.example.demo.controller;

import com.example.demo.entity.*;
import com.example.demo.service.FieldService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
public class FieldController {
    @Autowired
    private FieldService fieldService;

    @PostMapping("/insertField")
    public String insertField(@RequestBody String field1) {
        return fieldService.insertMyField(field1);
    }

    @PostMapping("/getListField")
    public String getListField() {
//         CompletableFuture<String> future = fieldService.getListFieldNew();
//         return future.join();
        return fieldService.getFirebaseData();
    }

    @PostMapping("/getUpdateListField")
    public String getUpdateListField() {
        fieldService.updateFirebaseData();
//         CompletableFuture<String> future = fieldService.getListField();
        return fieldService.getFirebaseData();
        // return fieldService.getFieldsFromCache();
    }

    @PostMapping("/updateWeatherData")
    public void updateWeatherData() {
        try {
            fieldService.updateWeatherData("field2");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/updateHumidity")
    public void updateHumidity() {
        try {
            fieldService.updateHumidity("Field1");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/getModelField")
    public String getModelField(@RequestBody String fieldName) throws IOException {
        return fieldService.getModelField();
    }


   // @PostMapping("/calculateModel")
   // public String caculateModel(@RequestBody String fieldName) throws IOException {
   //     return FieldService.calculateModel(fieldName).join();
        //return fieldService.getModelField();
  //  }

    @PostMapping("/getWeatherData")
    public CompletableFuture<List<MeasuredData>> getWeatherData(@RequestBody String fieldName) {
        return FieldService.getWeatherData(fieldName);
    }


    @PostMapping("/deleteField")
    public void deleteField(@RequestBody String fieldName) {
      //  FieldService.deleteField(fieldName);
    }

    @PostMapping("/updateCustomizedParameters")
    public String updateCustomizedParameters(@RequestBody FieldDTO input) {
        return FieldService.updateCustomizedParameters(input);
    }

    @PostMapping("/setIrrigation")
    public String setIrrigation(@RequestBody String input) {
        return FieldService.setIrrigation(input);
    }

    @PostMapping("/getHistoryIrrigation")
    public CompletableFuture<List<HistoryIrrigation>> getHistoryIrrigation(@RequestBody String input) {
        return FieldService.getHistoryIrrigation(input);
    }

    @PostMapping("/getHumidity")
    public CompletableFuture<List<Humidity>> getHumidity(@RequestBody String input) {
        return FieldService.getHumidity(input);
    }

    @PostMapping("/getHumidityRecentTime")
    public CompletableFuture<Humidity> getHumidityRecentTime(@RequestBody String input) {
        return FieldService.getHumidityRecentTime(input);
    }

    @PostMapping("/getDisease")
    public CompletableFuture<List<Disease>> getDisease(@RequestBody String fieldName) {
        return FieldService.getDisease(fieldName);
    }

    @PostMapping("/getField")
    public CompletableFuture<FieldDTO> getField(@RequestBody String fieldName) {
        return FieldService.getField(fieldName);
    }

    @PostMapping("/calculateCSV")
    public String calculateCSV(@RequestBody List<WeatherRequest> weather) throws IOException {
        if (weather == null || weather.isEmpty()) {
            System.out.println("No data received in request, proceeding with CSV file data only");
            // Still proceed with CSV processing
        } else {
            System.out.println("Received " + weather.size() + " weather records in API request");
            for (WeatherRequest item : weather) {
                System.out.println("Weather item: " + item);
            }
            // You could choose to combine this data with CSV data if needed
        }
        return FieldService.calculateCSV(weather);
    }

//    @PostMapping("/calculateCSV")
//    public String calculateCSV(@RequestBody(required = false) List<WeatherRequest> weather) throws IOException {
//        if (weather == null || weather.isEmpty()) {
//            System.out.println("No data received, loading from CSV file...");
//            Field.loadAllWeatherDataFromCsvFile();
//        }
//        return fieldService.calculateCSV(weather);
//    }


    public static List<List<Object>> convertJsonToList(String jsonString) {
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            // Sử dụng TypeReference để chuyển đổi JSON thành List<List<Object>>
            return objectMapper.readValue(jsonString, new TypeReference<List<List<Object>>>() {});
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
    @PostMapping("/calculateExcel")
    public String calculateExcel() throws IOException {
        // Sử dụng ObjectMapper để chuyển đổi chuỗi JSON thành danh sách 2 chiều
        return FieldService.calculateExcel();
    }

}
