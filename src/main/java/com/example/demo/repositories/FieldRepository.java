package com.example.demo.repositories;

import com.example.demo.entity.*;
import com.example.demo.service.Mongo.WeatherProvider;
import com.google.firebase.database.*;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.example.demo.service.FieldService.getWeatherData;

@Repository
public class FieldRepository {
    // 1. Thêm dòng này để Spring Boot tự động tiêm WeatherProvider vào
    @Autowired
    private WeatherProvider weatherProvider;
    public CompletableFuture<String> getListField() {
        CompletableFuture<String> future = new CompletableFuture<>();
        DatabaseReference dataRef = FirebaseDatabase.getInstance().getReference("user");
        dataRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                List<FieldDTO> fieldList = new ArrayList<>();
                for (DataSnapshot child : dataSnapshot.getChildren()) {
                    FieldDTO fieldDTO = mapField(child);
                    fieldList.add(fieldDTO);
                }
                Gson gson = new Gson();
                String json = gson.toJson(fieldList);
                future.complete(json);
            }
            @Override
            public void onCancelled(DatabaseError databaseError) {
                future.completeExceptionally(databaseError.toException());
            }
        });
        return future;
    }

    public FieldDTO mapField(DataSnapshot dataSnapshot) {
        try {
            FieldDTO fieldDTO = new FieldDTO();
            fieldDTO.setFieldName(dataSnapshot.getKey());
            // fieldDTO.setdAP(dataSnapshot.child("dAP").getValue(Integer.class));
            fieldDTO.setStartTime(dataSnapshot.child("startTime").getValue(String.class));
            fieldDTO.setCustomized_parameters(dataSnapshot.child("customized_parameters").getValue(CustomizedParameters.class));
            fieldDTO.setStartIrrigation(dataSnapshot.child("startIrrigation").getValue(String.class));
            fieldDTO.setIrrigationCheck(dataSnapshot.child("irrigationCheck").getValue(String.class));
            fieldDTO.setIrrigation_information(dataSnapshot.child("irrigation_information").getValue(IrrigationInformation.class));
            fieldDTO.setHistoryIrrigation(dataSnapshot.child("historyIrrigation").getValue(HistoryIrrigation.class));
            return fieldDTO;
        } catch (Exception e) {
            return null;
        }
    }

    public String getModelField() throws IOException {
        Field fieldTest = new Field("fieldTest");
      //  fieldTest.runModel();
        // 1. Khởi tạo mốc thời gian muốn chạy mô phỏng
        // Ví dụ: Lấy từ 1/7/2023 đến 31/7/2023
        Date startDate = new Date(2023 - 1900, 6, 1);
        Date endDate = new Date(2023 - 1900, 6, 31);

        // 2. Gọi runModel với cấu trúc mới
     //   fieldTest.runModel(weatherProvider, startDate, endDate);
        Gson gson = new Gson();
        String json = gson.toJson(fieldTest._results);
        return json;
    }

//    public String caculateModel(String input) {
//        return "";
//    }
}
