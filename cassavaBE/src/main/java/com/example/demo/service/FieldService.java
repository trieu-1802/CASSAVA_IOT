package com.example.demo.service;

import com.example.demo.entity.*;
import com.example.demo.repositories.FieldRepository;
import com.example.demo.repositories.FirebaseRepository;
import com.example.demo.service.Mongo.WeatherProvider;
import com.google.firebase.database.*;
import com.google.gson.Gson;
import com.opencsv.CSVWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.Year;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import com.example.demo.entity.Field;

import static com.example.demo.entity.Field._weatherData;

@Service
@EnableAsync
public class FieldService {
    // 1. Thêm biến static cho WeatherProvider
    private static WeatherProvider weatherProvider;
    private static FieldRepository fieldRepository;
    private static FirebaseRepository firebaseRepository;

    @Autowired
    public FieldService(FieldRepository fieldRepository, FirebaseRepository firebaseRepository) {
        this.fieldRepository = fieldRepository;
        this.firebaseRepository = firebaseRepository;
    }

    @Scheduled(cron = "0 30 7 * * ?")
    public void myScheduledMethod() {
        JSONArray jsonArray = new JSONArray(getFirebaseData());
        for (int i = 0; i < jsonArray.length(); i++) {
            calculateModel(jsonArray.getJSONObject(i).getString("fieldName"));
        }
    }


    public static CompletableFuture<FieldDTO> getField(String nameField) {
        CompletableFuture<FieldDTO> future = new CompletableFuture<>();
        DatabaseReference dataRef = FirebaseDatabase.getInstance().getReference("user/" + nameField);
        dataRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                FieldDTO fieldDTO = mapField(dataSnapshot);
                future.complete(fieldDTO);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                future.completeExceptionally(databaseError.toException());
            }
        });
        return future;
    }

//    @Scheduled(fixedRate = 300000) // 300000 milliseconds = 5 minutes
//    public void refreshFieldListInCache() {
//        updateFieldListCache().join(); // Đợi cho đến khi hoàn thành
//    }
//
//    @CachePut(value = "fieldListCache")
//    public CompletableFuture<String> updateFieldListCache() {
//        return getListField(); // Gọi phương thức đã tồn tại để lấy dữ liệu
//    }

//    @Cacheable(value = "fieldListCache")
//    public CompletableFuture<String> getFieldListFromCache() {
//        // Phương thức này sẽ chỉ được thực hiện nếu dữ liệu không có trong cache
//        // Trong trường hợp này, chúng ta giả sử rằng nếu không có cache, phương thức sẽ trả về null
//        return CompletableFuture.completedFuture(null);
//    }

//    public String getListFieldCache() {
//        return getFieldListFromCache().join();
//    }

    private static final Map<String, String> cache = new ConcurrentHashMap<>();

    // Hàm cập nhật cache
//    @Scheduled(fixedRate = 300000) // 300000 ms = 5 minutes
//    public void updateCache() {
//        getListField().thenAccept(json -> cache.put("fields", json));
//    }
//
//    @Scheduled(fixedRate = 600000) // 300000 ms = 5 minutes
//    public void updateWeatherCache() {
//        getWeatherData("field1").thenAccept(json -> cache.put("weather", json.toString()));
//    }

    // Hàm lấy dữ liệu từ cache
//    public static String getFieldsFromCache() {
//        String data = cache.get("fields");
//        // Nếu dữ liệu không có trong cache
//        if (data == null) {
//            // Tải dữ liệu từ nguồn chính
//            data = getListField().join();
//
//            // Cập nhật cache với dữ liệu mới
//            cache.put("fields", data);
//        }
//        return data;
//    }

    @Cacheable("fields")
    public String getFirebaseData() {
        return getListField().join();
    }

    @CacheEvict("fields")
    public void updateFirebaseData() {
    }

    // oki
    public static CompletableFuture<String> getListField() {
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
                cache.put("fields", json);
                future.complete(json);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                future.completeExceptionally(databaseError.toException());
            }
        });
        return future;
    }


    public static FieldDTO mapField(DataSnapshot dataSnapshot) {
        try {
            FieldDTO fieldDTO = new FieldDTO();
            fieldDTO.setFieldName(dataSnapshot.getKey());
            // fieldDTO.setdAP(dataSnapshot.child("dAP").getValue(Integer.class));
            fieldDTO.setStartTime(dataSnapshot.child("startTime").getValue(String.class));
            fieldDTO.setCustomized_parameters(dataSnapshot.child("customized_parameters").getValue(CustomizedParameters.class));
            fieldDTO.setStartIrrigation(dataSnapshot.child("startIrrigation").getValue(String.class));
            fieldDTO.setEndIrrigation(dataSnapshot.child("endIrrigation").getValue(String.class));
            fieldDTO.setIrrigationCheck(dataSnapshot.child("irrigationCheck").getValue(String.class));
            fieldDTO.setIrrigation_information(dataSnapshot.child("irrigation_information").getValue(IrrigationInformation.class));
            fieldDTO.setHistoryIrrigation(dataSnapshot.child("historyIrrigation").getValue(HistoryIrrigation.class));
            return fieldDTO;
        } catch (Exception e) {
            return null;
        }
    }

    public static CompletableFuture<List<MeasuredData>> getWeatherData(String input) {
        CompletableFuture<List<MeasuredData>> future = new CompletableFuture<>();
        String sanitizedInput = input.replace("\"", "").trim();
        DatabaseReference dataRef = FirebaseDatabase.getInstance().getReference("user/" + input + "/measured_data");
//        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
//        DatabaseReference dataRef = FirebaseDatabase.getInstance().getReference("user/" + sanitizedInput + "/measured_data/" + today);

        System.out.println("[DEBUG] Đang truy vấn Firebase path: user/" + sanitizedInput + "/measured_data/");
        dataRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                //System.out.println("[DEBUG] Dữ liệu Firebase nhận được cho " + input + ": " + dataSnapshot.getValue());
                List<MeasuredData> measuredDataList = new ArrayList<>();

                for (DataSnapshot child : dataSnapshot.getChildren()) {
                    for (DataSnapshot child1 : child.getChildren()) {
                        MeasuredData measuredData = child1.getValue(MeasuredData.class);
                        measuredDataList.add(measuredData);
                    }
                }
                if (dataSnapshot.getValue() == null) {
                     future.complete(null);
                } else {
                    measuredDataList.sort(Comparator.comparing(MeasuredData::getTime).reversed());
                    future.complete(measuredDataList);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                future.completeExceptionally(databaseError.toException());
            }
        });
        return future;
    }

//public static CompletableFuture<List<MeasuredData>> getWeatherData(String input) {
//    CompletableFuture<List<MeasuredData>> future = new CompletableFuture<>();
//    DatabaseReference dataRef = FirebaseDatabase.getInstance().getReference("user/" + input + "/measured_data");
//    dataRef.addListenerForSingleValueEvent(new ValueEventListener() {
//        @Override
//        public void onDataChange(DataSnapshot dataSnapshot) {
//            List<MeasuredData> measuredDataList = new ArrayList<>();
//            for (DataSnapshot child : dataSnapshot.getChildren()) {
//                for (DataSnapshot child1 : child.getChildren()) {
//                    MeasuredData measuredData = child1.getValue(MeasuredData.class);
//                    measuredDataList.add(measuredData);
//                }
//            }
//            if (dataSnapshot.getValue() == null) {
//                future.complete(null);
//            } else {
//                future.complete(measuredDataList);
//            }
//        }
//
//        @Override
//        public void onCancelled(DatabaseError databaseError) {
//            future.completeExceptionally(databaseError.toException());
//        }
//    });
//    return future;
//}


//    public static List<MeasuredData> readCSVFile(String filePath) throws IOException {
//        List<MeasuredData> measuredDataList = new ArrayList<>();
//
//        try (FileReader fileReader = new FileReader(filePath);
//             CSVParser csvParser = new CSVParser(fileReader, CSVFormat.DEFAULT)) {
//            int i = 0;
//            for (CSVRecord csvRecord : csvParser) {
//                if (i == 0) {
//                    i++;
//                    continue;
//                }
//                MeasuredData measuredData = new MeasuredData();
//                measuredData.setRainFall(Double.parseDouble(csvRecord.get(3)));
//                measuredData.setRelativeHumidity(Double.parseDouble(csvRecord.get(4)));
//                measuredData.setTemperature(Double.parseDouble(csvRecord.get(5)));
//                measuredData.setWindSpeed(Double.parseDouble(csvRecord.get(6)));
//                measuredData.setRadiation(Double.parseDouble(csvRecord.get(2)));
//                measuredData.setTime(csvRecord.get(0));
//
//                measuredDataList.add(measuredData);
//            }
//        }
//        return measuredDataList;
//    }

//    public static void upDateWeatherData() {
//        String filePath = "C:\\Users\\Admin\\Downloads\\weather_data.xlsx"; // Đặt đường dẫn đến tệp CSV của bạn
//        try {
//            List<MeasuredData> measuredDataList = readCSVFile(filePath);
//            System.out.println(measuredDataList.size());
//            // Bây giờ bạn có thể làm gì đó với danh sách measuredDataList
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }

    public static void updateWeatherData(String name) throws IOException {
        List<List<Object>> weatherData = new ArrayList<>();
        String path = "H:\\demo1\\dataWeatherVietNam2.csv";
        File csvFile = new File(path);
        FirebaseDatabase database = FirebaseDatabase.getInstance();

        FileReader fileReader = new FileReader(csvFile);
        CSVParser csvParser = new CSVParser(fileReader, CSVFormat.DEFAULT);
        // Định dạng cho ngày và giờ
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        for (CSVRecord record : csvParser) {
            List<Object> rowData = new ArrayList<>();
            for (String value : record) {
                rowData.add(value);
            }
            weatherData.add(rowData);
        }
        int i = 0;
        try {
            List<MeasuredData> measuredDataList = new ArrayList<>();
            for (i = 3; i < weatherData.size(); i++) {
                MeasuredData measuredData = new MeasuredData(name);
                measuredData.setTime(weatherData.get(i).get(5).toString());
                measuredData.setRadiation(Float.parseFloat(weatherData.get(i).get(4).toString()));
                measuredData.setRainFall(Double.parseDouble(weatherData.get(i).get(0).toString()));
                measuredData.setRelativeHumidity(Double.parseDouble(weatherData.get(i).get(1).toString()));
                measuredData.setWindSpeed(Double.parseDouble(weatherData.get(i).get(3).toString()));
                measuredData.setTemperature(Double.parseDouble(weatherData.get(i).get(2).toString()));
                measuredDataList.add(measuredData);
                try {
                    // Chuyển đổi chuỗi thời gian thành đối tượng Date
                    Date date = dateFormat.parse(measuredData.getTime());

                    // Tách ngày và giờ từ đối tượng Date
                    SimpleDateFormat dateOnlyFormat = new SimpleDateFormat("yyyy-MM-dd");
                    SimpleDateFormat timeOnlyFormat = new SimpleDateFormat("HH:mm:ss");


                    String datePart = dateOnlyFormat.format(date);
                    String timePart = timeOnlyFormat.format(date);
                    measuredData.setTime(datePart + " " + timePart);
                    DatabaseReference ref = database.getReference("user");

                    ref.child(name + "/measured_data/" + datePart + "/" + timePart).setValueAsync(measuredData);
                    if (i == 1) {
                        ref.child(name + "/startTime").setValueAsync(measuredData.getTime());
                    }
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            System.out.println(weatherData.get(i).get(0));
        }
        csvParser.close();
        fileReader.close();
    }

    public static void updateHumidity(String name) throws IOException {
        List<List<Object>> humidity = new ArrayList<>();
        String path = "H:\\demo1\\doam.csv";
        File csvFile = new File(path);
        FirebaseDatabase database = FirebaseDatabase.getInstance();

        FileReader fileReader = new FileReader(csvFile);
        CSVParser csvParser = new CSVParser(fileReader, CSVFormat.DEFAULT);
        // Định dạng cho ngày và giờ
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        for (CSVRecord record : csvParser) {
            List<Object> rowData = new ArrayList<>();
            for (String value : record) {
                rowData.add(value);
            }
            humidity.add(rowData);
        }
        int i = 0;
        try {
            List<Humidity> humidityList = new ArrayList<>();
            for (i = 0; i < humidity.size(); i++) {
                Humidity humidity1 = new Humidity((Double) humidity.get(i).get(2), (Double) humidity.get(i).get(3), humidity.get(i).get(1).toString());
                humidityList.add(humidity1);
                try {
                    // Chuyển đổi chuỗi thời gian thành đối tượng Date
                    Date date = dateFormat.parse(humidity1.getTime());

                    // Tách ngày và giờ từ đối tượng Date
                    SimpleDateFormat dateOnlyFormat = new SimpleDateFormat("yyyy-MM-dd");
                    SimpleDateFormat timeOnlyFormat = new SimpleDateFormat("HH:mm:ss");


                    String datePart = dateOnlyFormat.format(date);
                    String timePart = timeOnlyFormat.format(date);
                    humidity1.setTime(datePart + " " + timePart);
                    DatabaseReference ref = database.getReference("user");

                    ref.child(name + "/humidity_minute/" + datePart + "/" + timePart).setValueAsync(humidity1);
                } catch (ParseException e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            System.out.println(humidity.get(i).get(0));
        }
        csvParser.close();
        fileReader.close();
    }

    // Thêm test thử trường hợp dùng repository
    public static CompletableFuture<String> getListFieldNew() {
        CompletableFuture<String> lstField = fieldRepository.getListField();
        return lstField;
    }

    public String getModelField() throws IOException {
        return fieldRepository.getModelField();
    }

    @Async
    @EventListener
    public CompletableFuture<String> calculateModel(String nameField) {
        CompletableFuture<FieldDTO> fieldDTOFuture = getField(nameField);
        System.out.println("Giá trị thực tế của nameField: [" + nameField + "]");
        // Loại bỏ dấu " ở đầu và cuối chuỗi nếu có
        String sanitizedFieldName = nameField.replace("\"", "").trim();
        //String firebasePath = "user/" + sanitizedFieldName + "/measured_data/2025-03-25";
        //System.out.println("[DEBUG] 🔍 Firebase path after processing: " + firebasePath);

        CompletableFuture<List<MeasuredData>> measureDataListFuture = getWeatherData(sanitizedFieldName);
        return CompletableFuture.allOf(fieldDTOFuture, measureDataListFuture)
                .thenApply(ignored -> {
                    try {
                        double doyOfStartTime = 0.0;
                        List<MeasuredData> measuredDataList = measureDataListFuture.join();
                        System.out.println("Weather data for " + nameField + ": " + measuredDataList);
                        if (measuredDataList == null || measuredDataList.size() == 0) {
                            return "NODATA";
                        }
                        Field field = new Field(nameField);
                        //Field field = new Field("nameField");

                        Date startTime;
                        LocalDate localDateStart;
                        int yearStart = 2023;
                        double timeYear = 365;
                        try {
                            CustomizedParameters cp = fieldDTOFuture.get().getCustomized_parameters();
                            field.acreage = cp.getAcreage();
                            field.fieldCapacity = cp.getFieldCapacity();
                            field.distanceBetweenRow = cp.getDistanceBetweenRow();
                            field.distanceBetweenHole = cp.getDistanceBetweenHole();
                            field.dripRate = cp.getDripRate();
                            field.autoIrrigation = cp.isAutoIrrigation();
                            field.numberOfHoles = cp.getNumberOfHoles();
                            field.fertilizationLevel = cp.getFertilizationLevel();
                            field.irrigationDuration = cp.getIrrigationDuration();
                            field.scaleRain = cp.getScaleRain();
                            String start = fieldDTOFuture.get().getStartTime();
                            startTime = convertStringtoDate(start);
                            localDateStart = startTime.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                            yearStart = localDateStart.getYear();
                            timeYear = Year.isLeap(yearStart) ? 366.0 : 365.0;
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        } catch (ExecutionException e) {
                            throw new RuntimeException(e);
                        }
                        List<List<Object>> weatherData = new ArrayList<>();
                        for (MeasuredData measuredData : measuredDataList) {
                            String time = measuredData.getTime();
                            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                            Date date;
                            try {
                                date = dateFormat.parse(time);
                                // So sánh ngày bắt đầu trồng và ngày của dữ liệu thời tiết
                                LocalDate localDate1 = date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
                                int yearWeather = localDate1.getYear();

                                double doy = yearWeather == yearStart ? field.getDoy(date) : field.getDoy(date) + timeYear;
                                double radiation = measuredData.getRadiation();
                                double rain = measuredData.getRainFall();
                                double relative = measuredData.getRelativeHumidity();
                                double temperature = measuredData.getTemperature();
                                double wind = measuredData.getWindSpeed();

                                List<Object> result = new ArrayList<>();
                                result.add(time);
                                result.add(doy);
                                result.add(radiation);
                                result.add(rain);
                                result.add(relative);
                                result.add(temperature);
                                result.add(wind);

                                weatherData.add(result);
                            } catch (ParseException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        List<List<Object>> weatherDataTemp = new ArrayList<>();
                        weatherDataTemp.add(weatherData.get(0));

                        for (int i = 0; i < weatherData.size() - 2; i++) {
                            Double dt = Double.parseDouble(weatherData.get(i + 1).get(1).toString()) -
                                    Double.parseDouble(weatherDataTemp.get(weatherDataTemp.size() - 1).get(1).toString());
                            if (dt >= 0.01) {
                                weatherDataTemp.add(weatherData.get(i + 1));
                            }
                        }
                        _weatherData = new ArrayList<>();
                        for (int i = 0; i < weatherDataTemp.size() - 1; i++) {
                            if (Double.parseDouble(weatherDataTemp.get(i).get(1).toString()) >= 224) {
                                _weatherData.add(weatherDataTemp.get(i));
                            }
                        }
                        field.simulate();

                        // cập nhật lượng nc tưới lên firebase đối với những cánh đồng tưới tự động
                        if (field.autoIrrigation) {
                            int length = field._results.get(2).size();
                            double irr = (length > 1)
                                    ? field._results.get(2).get(length - 1) - field._results.get(2).get(length - 2)
                                    : field._results.get(2).get(0);
                            irr *= 0.1; // convert to l/m2
                            double duration = irr *
                                    field.acreage /
                                    (field.dripRate *
                                            field.numberOfHoles) *
                                    3600; // convert to seconds

                            LocalDateTime day = getDay(field._results.get(8).get(length - 1));
                            day = day.plusHours(8);
                            // set thời gian tưới là 8h
                            LocalDateTime d = LocalDateTime.of(day.getYear(), day.getMonthValue(), day.getDayOfMonth(),
                                    8, day.getMinute(), day.getSecond());
                            DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
                            String formattedDate = d.format(outputFormatter);
                            IrrigationInformation irrigationInformation = new IrrigationInformation(formattedDate, irr, duration);

                            //String sanitizedFieldName = nameField.replace("\"", "").replaceAll("[.#$\\[\\]]", "_");
                            DatabaseReference databaseReference = FirebaseDatabase.getInstance().getReference();

                            // databaseReference.child("user/" + nameField + "/irrigation_information").setValue(irrigationInformation, new DatabaseReference.CompletionListener() {
                            databaseReference.child("user").child(sanitizedFieldName).child("irrigation_information").setValue(irrigationInformation, new DatabaseReference.CompletionListener() {

                            @Override
                                public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                                    if (databaseError != null) {
                                        System.out.println("Data could not be saved: " + databaseError.getMessage());
                                    } else {
                                        System.out.println("Data saved successfully.");
                                    }
                                }
                            });
                            if (irr > 0) {
                                DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

                                String formattedDate1 = d.format(formatter1);
                                HistoryIrrigation historyIrrigation = new HistoryIrrigation(d.format(formatter1), "admin", irr, duration);
                                //databaseReference.child("/user" + nameField + "/historyIrrigation" + formattedDate1).setValue(historyIrrigation, new DatabaseReference.CompletionListener() {
                                databaseReference.child("user").child(sanitizedFieldName).child("historyIrrigation").setValue(historyIrrigation, new DatabaseReference.CompletionListener() {
                                    @Override
                                    public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                                        if (databaseError != null) {
                                            System.out.println("Data could not be saved: " + databaseError.getMessage());
                                        } else {
                                            System.out.println("Data saved successfully.");
                                        }
                                    }
                                });
                            }
                        }
                        Gson gson = new Gson();
                        String json = gson.toJson(field._results);
                        return json;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    public static String calculateCSV(List<WeatherRequest> nameField) throws IOException {
        //Field fieldTest = new Field("fieldTest");
//        List<List<Object>> twoDimensionalList = nameField.stream()
//                .map(item -> List.of(
//                        (Object)item.doy, (Object)item.rain, (Object)item.dt,
//                        (Object)item.temp, (Object)item.radiation, (Object)item.relativeHumidity,
//                        (Object)item.wind, (Object)item.lat, (Object)item.lon,
//                        (Object)item.elev, (Object)item.height, (Object)item.irr))
//                .collect(Collectors.toList());
//        fieldTest._weatherData = new ArrayList<>();
//        fieldTest._weatherData = twoDimensionalList;
        Field fieldTest = new Field("fieldTest");
        //fieldTest.autoIrrigation = true;

        // This method will load data from CSV and set it to fieldTest._weatherData
       // fieldTest.loadAllWeatherDataFromCsvFile();

        // Run the simulation and other processing on the loaded CSV data
      //  fieldTest.runModel();
        // 1. Khởi tạo mốc thời gian muốn chạy mô phỏng
        // Ví dụ: Lấy từ 1/7/2023 đến 31/7/2023
        Date startDate = new Date(2023 - 1900, 6, 1);
        Date endDate = new Date(2023 - 1900, 6, 31);

        // 2. Gọi runModel với cấu trúc mới
       // fieldTest.runModel(weatherProvider, startDate, endDate);

        // Return the results as JSON
        Gson gson = new Gson();
        String json = gson.toJson(fieldTest._results);
        return json;
    }



    private Object safeGet(Object value) {
        return value != null ? value : "N/A"; // Trả về "N/A" nếu null
    }

    public static String calculateExcel() {

        return "";
    }
    public LocalDateTime getDay(double day) {
        LocalDateTime r = LocalDateTime.now();
        LocalDateTime rsd = LocalDateTime.of(r.getYear(), 1, 1, 0, 0);

        r = rsd.plusDays((long) Math.ceil(day));
        r = r.truncatedTo(java.time.temporal.ChronoUnit.DAYS);

        return r;
    }

    //Xóa một cánh đồng
    @CacheEvict("fields")
    public void deleteField(String field) {
        DatabaseReference database = FirebaseDatabase.getInstance().getReference("user");
        database.child(field).removeValue(new DatabaseReference.CompletionListener() {
            @Override
            public void onComplete(DatabaseError error, DatabaseReference ref) {
                if (error == null) {
                    System.out.println("Field deleted successfully");
                } else {
                    System.out.println("Error deleting field: " + error.getMessage());
                }
            }
        });
    }

    // oki
    public String insertMyField(String input) {
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        DatabaseReference ref = database.getReference("user");
        JSONObject jsonData = new JSONObject(input);
        String nameField = jsonData.optString("fieldName");
        FieldDTO fieldDTO = new FieldDTO(nameField);
        final String[] result = {""};
        ref.child(nameField).setValue(fieldDTO, new DatabaseReference.CompletionListener() {
            @Override
            public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                if (databaseError != null) {
                    // Xử lý lỗi nếu có
                    result[0] = "Data could not be saved. " + databaseError.getMessage();
                    updateFirebaseData();
                } else {
                    // Ghi dữ liệu thành công
                    result[0] = "Data saved successfully.";
                    // updateCache();
                }
            }
        });
        return result[0];
    }

    public static String updateCustomizedParameters(FieldDTO input) {
        try {
            DatabaseReference databaseReference = FirebaseDatabase.getInstance().getReference();
            databaseReference.child("user/" + input.getFieldName() + "/customized_parameters").setValue(input.getCustomized_parameters(), new DatabaseReference.CompletionListener() {
                @Override
                public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                    if (databaseError != null) {
                        System.out.println("Data could not be saved: " + databaseError.getMessage());
                    } else {
                        System.out.println("Data saved successfully.");
                    }
                }
            });
            return "OK";
        } catch (Exception e) {
            return e.toString();
        }
    }

    public static String setIrrigation(String input) {
        try {
            JSONObject jsonData = new JSONObject(input);
            String nameField = jsonData.optString("fieldName");
            String time = jsonData.optString("time");

            DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");
            DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
            LocalDateTime dateTime = LocalDateTime.parse(time, inputFormatter);

            String dateSetIrr = dateTime.format(outputFormatter);
            DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

            String formattedDate1 = dateTime.format(formatter1);
            Double amount = jsonData.optDouble("amount", 0);
            Double duration = jsonData.optDouble("duration", 0);
            String user = jsonData.optString("userName");

            IrrigationInformation irrigationInformation = new IrrigationInformation(dateSetIrr, amount, duration);
            DatabaseReference databaseReference = FirebaseDatabase.getInstance().getReference();
            databaseReference.child("user/" + nameField + "/irrigation_information").setValue(irrigationInformation, new DatabaseReference.CompletionListener() {
                @Override
                public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                    if (databaseError != null) {
                        System.out.println("Data could not be saved: " + databaseError.getMessage());
                    } else {
                        System.out.println("Data saved successfully.");
                    }
                }
            });
            HistoryIrrigation historyIrrigation = new HistoryIrrigation(formattedDate1, user, amount, duration);

            databaseReference.child("user/" + nameField + "/historyIrrigation/" + formattedDate1).setValue(historyIrrigation, new DatabaseReference.CompletionListener() {
                @Override
                public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                    if (databaseError != null) {
                        System.out.println("Data could not be saved: " + databaseError.getMessage());
                    } else {
                        System.out.println("Data saved successfully.");
                    }
                }
            });

            return "OK";
        } catch (Exception e) {
            return e.toString();
        }
    }

    public static CompletableFuture<List<HistoryIrrigation>> getHistoryIrrigation(String input) {
        CompletableFuture<List<HistoryIrrigation>> future = new CompletableFuture<>();
        DatabaseReference dataRef = FirebaseDatabase.getInstance().getReference("user/" + input + "/historyIrrigation");
        dataRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                List<HistoryIrrigation> historyIrrigationList = new ArrayList<>();
                for (DataSnapshot child : dataSnapshot.getChildren()) {

                    HistoryIrrigation historyIrrigation = child.getValue(HistoryIrrigation.class);
                    historyIrrigationList.add(historyIrrigation);
                }
                if (dataSnapshot.getValue() == null) {
                    future.complete(null);
                } else {
                    future.complete(historyIrrigationList);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                future.completeExceptionally(databaseError.toException());
            }
        });
        return future;
    }

    public static CompletableFuture<List<Humidity>> getHumidity(String input) {
        CompletableFuture<List<Humidity>> future = new CompletableFuture<>();
        DatabaseReference dataRef = FirebaseDatabase.getInstance().getReference("user/" + input + "/humidity_minute");
        dataRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                List<Humidity> humidityList = new ArrayList<>();
                for (DataSnapshot child : dataSnapshot.getChildren()) {
                    for (DataSnapshot child1 : child.getChildren()) {
                        Humidity measuredData = child1.getValue(Humidity.class);
                        humidityList.add(measuredData);
                    }
                }
                if (dataSnapshot.getValue() == null) {
                    future.complete(null);
                } else {
                    future.complete(humidityList);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                future.completeExceptionally(databaseError.toException());
            }
        });
        return future;
    }

    public static CompletableFuture<Humidity> getHumidityRecentTime(String input) {
        CompletableFuture<Humidity> future = new CompletableFuture<>();
        DatabaseReference dataRef = FirebaseDatabase.getInstance().getReference("user/" + input + "/humidity_minute");
        dataRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                List<Humidity> humidityList = new ArrayList<>();
                for (DataSnapshot child : dataSnapshot.getChildren()) {
                    for (DataSnapshot child1 : child.getChildren()) {
                        Humidity measuredData = child1.getValue(Humidity.class);
                        humidityList.add(measuredData);
                    }
                }
                if (dataSnapshot.getValue() == null) {
                    future.complete(null);
                } else {
                    future.complete(humidityList.get(humidityList.size() - 1));
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                future.completeExceptionally(databaseError.toException());
            }
        });
        return future;
    }

    private void writeDataToCsvFile(List<List<Object>> result, String namePath) {
        String csvFilePath = namePath;
        try {
            FileWriter writer = new FileWriter(csvFilePath);
            CSVWriter csvWriter = new CSVWriter(writer);
            List<String[]> data = new ArrayList<>();
            data.add(new String[]{"time", "doy", "rainFall", "relativeHumidity", "temperature", "windSpeed", "radiation"});
            for (int i = 0; i < result.size(); i++) {
                data.add(new String[]{"", "", "", "", "", "", "", "", ""});
                for (int j = 0; j < result.get(0).size(); j++) {
                    data.get(i + 1)[j] = result.get(i).get(j).toString();
                }
            }
            csvWriter.writeAll(data);
            csvWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static CompletableFuture<List<Disease>> getDisease(String fieldName) {
        CompletableFuture<List<Disease>> future = new CompletableFuture<>();
        DatabaseReference dataRef = FirebaseDatabase.getInstance().getReference("user/" + fieldName + "/disease");
        dataRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                List<Disease> historyIrrigationList = new ArrayList<>();
                for (DataSnapshot child : dataSnapshot.getChildren()) {

                    Disease historyIrrigation = child.getValue(Disease.class);
                    historyIrrigationList.add(historyIrrigation);
                }
                if (dataSnapshot.getValue() == null) {
                    future.complete(null);
                } else {
                    future.complete(historyIrrigationList);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                future.completeExceptionally(databaseError.toException());
            }
        });
        return future;
    }

    public Date convertStringtoDate(String time) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        Date date;
        try {
            date = dateFormat.parse(time);
            return date;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


}

