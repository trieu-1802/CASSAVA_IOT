package com.example.demo.entity;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class FieldDTO {
    String fieldName; // add fieldName to identify different fields
    int dAP; // days after planting
    String startTime; // planting time

    public String getIrrigationCheck() {
        return irrigationCheck;
    }

    String irrigationCheck; // whether irrigation is needed
    double amountOfIrrigation; // amount of irrigation in mm

    List<Double> yields; // list of yields recorded
    String checkYieldDate; // date when yield was checked
    CustomizedParameters customized_parameters; // customized parameters for the field
    String measured_data; // measured data in JSON format
    String startIrrigation; // start time of irrigation

    String endIrrigation; // end time of irrigation
    double _autoIrrigateTime = -1; // time for auto irrigation
    IrrigationInformation irrigation_information; // information about irrigation
    HistoryIrrigation historyIrrigation; // history of irrigation

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public int getdAP() {
        return dAP;
    }

    public void setdAP(int dAP) {
        this.dAP = dAP;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

//    public String isIrrigationCheck() {
//        return irrigationCheck;
//    }

    public void setIrrigationCheck(String irrigationCheck) {
        this.irrigationCheck = irrigationCheck;
    }

    public double getAmountOfIrrigation() {
        return amountOfIrrigation;
    }

    public void setAmountOfIrrigation(double amountOfIrrigation) {
        this.amountOfIrrigation = amountOfIrrigation;
    }

    public List<Double> getYields() {
        return yields;
    }

    public void setYields(List<Double> yields) {
        this.yields = yields;
    }

    public String getCheckYieldDate() {
        return checkYieldDate;
    }

    public void setCheckYieldDate(String checkYieldDate) {
        this.checkYieldDate = checkYieldDate;
    }

    public CustomizedParameters getCustomized_parameters() {
        return customized_parameters;
    }

    public void setCustomized_parameters(CustomizedParameters customized_parameters) {
        this.customized_parameters = customized_parameters;
    }

    public String getMeasured_data() {
        return measured_data;
    }

    public void setMeasured_data(String measured_data) {
        this.measured_data = measured_data;
    }

    public String getStartIrrigation() {
        return startIrrigation;
    }

    public void setStartIrrigation(String startIrrigation) {
        this.startIrrigation = startIrrigation;
    }

    public String getEndIrrigation() {
        return endIrrigation;
    }

    public void setEndIrrigation(String endIrrigation) {
        this.endIrrigation = endIrrigation;
    }

    public double get_autoIrrigateTime() {
        return _autoIrrigateTime;
    }

    public void set_autoIrrigateTime(double _autoIrrigateTime) {
        this._autoIrrigateTime = _autoIrrigateTime;
    }

    public IrrigationInformation getIrrigation_information() {
        return irrigation_information;
    }

    public void setIrrigation_information(IrrigationInformation irrigation_information) {
        this.irrigation_information = irrigation_information;
    }

    public FieldDTO() {
    }

    public HistoryIrrigation getHistoryIrrigation() {
        return historyIrrigation;
    }

    public void setHistoryIrrigation(HistoryIrrigation historyIrrigation) {
        this.historyIrrigation = historyIrrigation;
    }

    public FieldDTO(String name) {
        LocalDateTime currentDateTime = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
        this.startTime = currentDateTime.format(formatter);
        this.dAP = 0;
        this.irrigationCheck = "false";
        this.amountOfIrrigation = 0;
        this.measured_data = "";
        this.checkYieldDate = "";
        this.customized_parameters = new CustomizedParameters(name);
        this.startIrrigation = "";
        this.endIrrigation = "";
    }

//    public FieldDTO(FieldDTO fieldDTO){
//        this.startTime = fieldDTO.startTime;
//        this.dAP = fieldDTO.dAP;
//        this.irrigationCheck = fieldDTO.irrigationCheck;
//        this.amountOfIrrigation = fieldDTO.amountOfIrrigation;
//        this.measured_data = "";
//        this.checkYieldDate = "";
//        this.customized_parameters = new CustomizedParameters(fieldDTO.customized_parameters);
//        this.startIrrigation = "";
//        this.endIrrigation = "";
//    }

}
