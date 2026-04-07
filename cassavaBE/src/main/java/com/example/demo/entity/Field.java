package com.example.demo.entity;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.opencsv.CSVWriter;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;

import static java.lang.Math.*;

public class Field {

    public static final double _APPi = 1.00 * 1.00; // Area per plant (row x interRow spacing) (m2)
    public static final int _nsl = 5; // number of soil layer
    public static final double _lw = 0.9 / _nsl; // depth/_nsl // thickness of a layer (m) _depth = 0.9
    public static final double _lvol = _lw * _APPi; // depth*_APPI/_nsl // volume of one soil layer
    public static final double _BD = 1360; // soild bulk density in (kg/m3) # Burrium 1.36, Ratchaburi 1.07 g.cm3
    public static double _cuttingDryMass = 75.4; // g
    public static double _leafAge = 75;
    public static double _SRL = 39.0; // m/g
    public static double _iStart = 91;
    public static double _iend = 361;
    public static boolean _zerodrain = true;
    // todo needs to be based on planting date provided by user then weather should start at right point
    public static double _iTheta = 0.2;
    public static double _thm = 0.18; //drier todo make
    public static double _ths = 0.43;//0.27; //0.43field capacity, not saturation todo rename
    public static double _thr = 0.065;//0.015; //0.065 residual water content
    public static double _thg = 0.02;
    public static double _rateFlow = 1.3;
    // order of value weather in list weatherData
    final int _iTime = 0;
    final int _iDOY = 1;
    final int _iRadiation = 2;
    final int _iRain = 4;
    final int _iRH = 5;
    final int _iTemp = 3;
    final int _iWind = 6;
    final int _iIrrigation = 8;
    // public static List<List<Object>> _weatherData = new ArrayList<List<Object>>();
    // Khai báo danh sách chịu được đọc/ghi đồng thời của Java
    public static List<List<Object>> _weatherData = new java.util.concurrent.CopyOnWriteArrayList<>();
    static String fieldName;
    public double _fcthresold;
    public double _IrrigationRate;
    public double _autoIrrigationDuration;
    // Thêm biến này để lưu trữ [Thời gian, thEquiv]
    public List<List<Double>> _equivData = new ArrayList<>(); // save value of equiv
    public List<HistoryIrrigation> listHistory = new ArrayList<>(); // luu tru lich su tuoi
    private static final String STATE_FILE_PATH = "simulation_state.csv";
    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    int dAP;
    String startTime;
    boolean irrigationCheck;
    double amountOfIrrigation;
    List<Double> yields;
    String checkYieldDate;

    // Flat params (matching MongoEntity/Field)
    public double acreage;
    public double fieldCapacity;
    public double distanceBetweenRow;
    public double distanceBetweenHole;
    public double dripRate;
    public boolean autoIrrigation;
    public int numberOfHoles;
    public double fertilizationLevel;
    public double irrigationDuration;
    public double scaleRain;

    MeasuredData measuredData;
    String startIrrigation;
    String endIrrigation;
    double _autoIrrigateTime = -1;
    public List<List<Double>> _results = new ArrayList<>();


    public Field(
            String fieldName,
            String startTime,
            int dAP,
            boolean irrigationCheck,
            double amountOfIrrigation,
            List<Double> yields,
            String checkYieldDate,
            double acreage, double fieldCapacity, double distanceBetweenRow,
            double distanceBetweenHole, double dripRate, boolean autoIrrigation,
            int numberOfHoles, double fertilizationLevel, double irrigationDuration,
            double scaleRain,
            MeasuredData measuredData,
            String startIrrigation,
            String endIrrigation) {
        this.fieldName = fieldName;
        this.startTime = startTime;
        this.dAP = dAP;
        this.irrigationCheck = irrigationCheck;
        this.amountOfIrrigation = amountOfIrrigation;
        this.yields = yields;
        this.checkYieldDate = checkYieldDate;
        this.acreage = acreage;
        this.fieldCapacity = fieldCapacity;
        this.distanceBetweenRow = distanceBetweenRow;
        this.distanceBetweenHole = distanceBetweenHole;
        this.dripRate = dripRate;
        this.autoIrrigation = autoIrrigation;
        this.numberOfHoles = numberOfHoles;
        this.fertilizationLevel = fertilizationLevel;
        this.irrigationDuration = irrigationDuration;
        this.scaleRain = scaleRain;
        this.measuredData = measuredData;
        this.startIrrigation = startIrrigation;
        this.endIrrigation = endIrrigation;
    }

    public Field(String name) {
        this.fieldName = name;
        this.startTime = String.valueOf(new Date(2023, 10, 20));
        this.dAP = 0;
        this.irrigationCheck = false;
        this.amountOfIrrigation = 0;
        this.yields = new ArrayList<>();
        this.yields.add(0.0);
        this.checkYieldDate = "";
        this.acreage = 50;
        this.fieldCapacity = 60;
        this.distanceBetweenHole = 30;
        this.irrigationDuration = 2;
        this.distanceBetweenRow = 100;
        this.dripRate = 1.6;
        this.fertilizationLevel = 100;
        this.scaleRain = 100;
        this.numberOfHoles = 8;
        this.autoIrrigation = true;
        this.measuredData = new MeasuredData(name);
        this.startIrrigation = "";
        this.endIrrigation = "";
    }

    public static double relTheta(double th) {
        return lim((th - _thr) / (_ths - _thr), 0, 1);
    }

    public static double lim(double x, double xl, double xu) {
        if (x > xu) {
            return xu;
        } else if (x < xl) {
            return xl;
        } else {
            return x;
        }
    }

    // convert Date to doy
    // Static biến dùng để nhớ offset và lần gọi trước
    private static double previousDoy = -1;
    private static double doyOffset = 0;

    public static double getDoy(Date sd) {
        Calendar rsd = Calendar.getInstance();
        rsd.setTime(sd);
        rsd.set(Calendar.MONTH, Calendar.JANUARY);
        rsd.set(Calendar.DAY_OF_MONTH, 1);
        rsd.set(Calendar.HOUR_OF_DAY, 0);
        rsd.set(Calendar.MINUTE, 0);
        rsd.set(Calendar.SECOND, 0);

        double doy = (double) ((sd.getTime() - rsd.getTime().getTime()) / (1000 * 60 * 60 * 24));
        doy += sd.getHours() / 24.0 +
                sd.getMinutes() / (24.0 * 60.0) +
                sd.getSeconds() / (24.0 * 60.0 * 60.0);

        // Nếu ngày bị "reset về đầu năm" (tức giảm so với ngày trước) → tăng offset
        if (previousDoy >= 0 && doy < previousDoy) {
            doyOffset += 365; // hoặc check năm nhuận nếu cần chính xác hơn
        }

        previousDoy = doy;

        return doy + doyOffset;
    }

    public static List<Double> multiplyLists(List<Double> l1, List<Double> l2) {
        int n = min(l1.size(), l2.size());
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            result.add(l1.get(i) * l2.get(i));
        }
        return result;
    }

    public static List<Double> multiplyListsWithConstant(List<Double> l, double c) {
        List<Double> result = new ArrayList<>();
        for (Double number : l) {
            result.add(number * c);
        }
        return result;
    }

    public static double monod(double conc, double Imax, double Km) {
        double pc = Math.max(0.0, conc);
        return pc * Imax / (Km + pc);
    }

    public static double logistic(double x, double x0, double xc, double k, double m) {
        return x0 + (m - x0) / (1 + exp(-k * (x - xc)));
    }

    public static double photoFixMean(double ppfd, double lai,
                                      double kdf, double Pn_max, double phi, double k) {
        double r = 0;
        int n = 30;
        double b = 4 * k * Pn_max;
        for (int i = 0; i < n; ++i) {
            double kf = exp(kdf * lai * (i + 0.5) / n);
            double I = ppfd * kf;
            double x0 = phi * I;
            double x1 = x0 + Pn_max;
            double p = x1 - sqrt(x1 * x1 - b * x0);
            r += p;
        }
        r *= -12e-6 * 60 * 60 * 24 * kdf * _APPi * lai / n / (2 * k);
        return r;
    }

    public static double fSLA(double ct) {
        return logistic(ct, 0.04, 60, 0.1, 0.0264);
    }

    public static double fKroot(double th, double rl) {
        double rth = relTheta(th);
        double kadj = min(1.0, pow(rth / 0.4, 1.5));
        double Ksr = 0.01;
        return Ksr * kadj * rl;
    }

    public static double fWaterStress(double minV, double maxV, double the) {
        double s = 1 / (maxV - minV);
        double i = -1 * minV * s;
        return lim(i + s * relTheta(the), 0, 1);
    }

    public static double getStress(double clab, double dm, double low, double high, boolean swap) {
        if (high < -9999.0) {
            high = low + 0.01;
        }
        double dm1 = Math.max(dm, 0.001);
        double cc = clab / dm1;
        double rr = lim(((cc - low) / (high - low)), 0, 1);

        if (swap) {
            rr = 1.0 - rr;
        }
        return rr;
    }

  /**
    public void loadAllWeatherDataFromCsvFile() throws IOException {
        List<List<Object>> weatherData = new ArrayList<>();
        resetDoyStaticFields();
        //  String path = "E:\\code\\cassavaBE\\src\\main\\java\\com\\example\\demo\\data\\data_fixed_night_radiation.csv";
        // String path = "E:\\code\\cassavaBE\\src\\main\\java\\com\\example\\demo\\data\\data_fixed_with_rain.csv";
        //  String path = "E:\\code\\cassavaBE\\src\\main\\java\\com\\example\\demo\\data\\data_with_thang7_muato.csv";
        //  String path = "/home/kien/Kin/LAB_IOT/version1/cassavaBE_JAVA/cassavaBE/src/main/java/com/example/demo/data/data_fixed_night_radiation_copy.csv";
        String path = "/home/kien/Kin/LAB_IOT/version2/CASSAVA_IOT/cassavaBE/src/main/java/com/example/demo/data/data_fixed_copy.csv";
        //String path = "E:\\code\\BE\\cassavaBE\\mqtt_weather_data1.csv";
        File csvFile = new File(path);
        FileReader fileReader = new FileReader(csvFile, StandardCharsets.UTF_8);
        CSVParser csvParser = new CSVParser(fileReader, CSVFormat.DEFAULT);

        List<List<Object>> twoDimensionalList = new ArrayList<>();
        for (CSVRecord record : csvParser) {
            //System.out.println("CSV Row: " + record);
            List<Object> rowData = new ArrayList<>();
            for (String value : record) {
                rowData.add(value);
            }
            twoDimensionalList.add(rowData);
            //System.out.println("Read CSV Row: " + rowData);
            weatherData.add(rowData);
        }

        //openweatherData
        for (int i = 0; i < weatherData.size() - 1; i++) {
            //String time = weatherData.get(i).get(0).toString();
            String time = weatherData.get(i).get(0).toString().replace("\uFEFF", "").trim();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date date;
            try {
                date = dateFormat.parse(time);
            } catch (ParseException e) {
                throw new RuntimeException(e);
            }
            weatherData.get(i).set(1, getDoy(date));
        }
        _weatherData = weatherData;

        List<WeatherRequest> nameField = new ArrayList<>();
        for (List<Object> row : _weatherData) {
            if (row.size() < 3) { // Kiểm tra xem có đủ cột không
                System.out.println("ERROR: Row has missing columns: " + row);
                continue;
            }

            WeatherRequest request = new WeatherRequest();
            request.setDoy(row.get(0) != null ? row.get(0).toString() : "N/A");
            request.setRain(row.get(1) != null ? row.get(1).toString() : "N/A");
            request.setDt(row.get(2) != null ? row.get(2).toString() : "N/A");

            nameField.add(request);
        }
        System.out.println("Converted " + nameField.size() + " records to WeatherRequest.");

//       List<List<Object>> weatherDataTemp = new ArrayList<>();
//       weatherData.get(0).set(7,0.1);
//       weatherDataTemp.add(weatherData.get(0));
//
//        for (int i = 0; i < weatherData.size() - 2; i++) {
//           Double dt = Double.parseDouble(weatherData.get(i + 1).get(1).toString()) -
//                   Double.parseDouble(weatherDataTemp.get(weatherDataTemp.size() - 1).get(1).toString());
//            if ( dt >= 0.01) {
//               weatherData.get(i + 1).set(7,dt);
//                weatherDataTemp.add(weatherData.get(i + 1));
//            }
//       }
//        _weatherData = weatherDataTemp;
//         //writeDataCsvFile(_weatherData);
        csvParser.close();
        fileReader.close();
    }

*/
  public void loadAllWeatherDataFromMongo(List<String> mongoData) {
      List<List<Object>> weatherData = new ArrayList<>();

      // SimpleDateFormat để parse chuỗi "yyyy-MM-dd HH:mm:ss" không lệch 7h
      SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
      dateFormat.setTimeZone(TimeZone.getTimeZone("UTC")); // Giữ nguyên giờ gốc như cậu muốn

      for (String line : mongoData) {
          // Cắt chuỗi theo dấu phẩy: [time, time, rad, temp, rain, hum, wind]
          String[] parts = line.split(",");
          if (parts.length < 7) continue;

          List<Object> rowData = new ArrayList<>();
          try {
              // Cột 0: Thời gian dạng String
              String timeStr = parts[0];
              Date date = dateFormat.parse(timeStr);

              // Chuyển đổi sang DOY (Day of Year) để model chạy được
              double doy = getDoy(date);

              // Add vào rowData theo đúng thứ tự mà hàm getWeatherData(t) yêu cầu
              rowData.add(timeStr);           // index 0: Time String
              rowData.add(doy);               // index 1: DOY
              rowData.add(parts[2]);          // index 2: Radiation
              rowData.add(parts[3]);          // index 3: Temperature
              rowData.add(parts[4]);          // index 4: Rain
              rowData.add(parts[5]);          // index 5: RH (Độ ẩm)
              rowData.add(parts[6]);          // index 6: Wind
              rowData.add("0.0");             // index 7: Placeholder
              rowData.add("0.0");             // index 8: Irrigation

              weatherData.add(rowData);
          } catch (ParseException e) {
              System.err.println("Lỗi parse thời gian từ Mongo: " + parts[0]);
          }
      }

      // Cập nhật biến static của class Field
      _weatherData = weatherData;
      System.out.println("✅ Đã load thành công " + _weatherData.size() + " bản ghi từ MongoDB vào Model.");
  }
    /** public void loadAllWeatherDataFromMqtt() {

     List<List<Object>> weatherData = new ArrayList<>();

     String broker = "tcp://broker.hivemq.com:1883";
     String topic = "/sensor/weatherStation";

     try {
     MqttClient client = new MqttClient(broker, MqttClient.generateClientId());
     client.connect();

     System.out.println("Connected to MQTT broker...");

     client.subscribe(topic, (t, msg) -> {

     String payload = new String(msg.getPayload());
     System.out.println("Received: " + payload);

     try {
     ObjectMapper mapper = new ObjectMapper();
     Map<String, Object> data = mapper.readValue(payload, Map.class);

     List<Object> rowData = new ArrayList<>();

     // 👇 đúng thứ tự CSV của bạn
     rowData.add(data.get("time1"));   // 0
     rowData.add(data.get("time2"));   // 1
     rowData.add(data.get("rad"));     // 2
     rowData.add(data.get("t"));       // 3
     rowData.add(data.get("rai"));     // 4
     rowData.add(data.get("h"));       // 5
     rowData.add(data.get("w"));       // 6

     // 👉 xử lý DOY giống code cũ
     String time = rowData.get(0).toString().replace("\uFEFF", "").trim();
     SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
     Date date = dateFormat.parse(time);

     rowData.set(1, getDoy(date)); // ghi đè time2 = DOY

     weatherData.add(rowData);

     System.out.println("Saved row: " + rowData);

     } catch (Exception e) {
     e.printStackTrace();
     }
     });

     // 👉 giữ chương trình chạy
     while (true) {
     Thread.sleep(1000);
     }

     } catch (Exception e) {
     e.printStackTrace();
     }

     _weatherData = weatherData;
     }
     */

    public double getIrrigationAmount() {
        int length = _results.get(2).size();
        double irr = (length > 1)
                ? _results.get(2).get(length - 1) - _results.get(2).get(length - 2)
                : _results.get(2).get(0);
        return irr * 0.1; // convert from m3/ha to l/m2
    }

    public void runModel() throws IOException {
        System.out.println("Calling loadAllWeatherDataFromCsvFile()...");
       // loadAllWeatherDataFromCsvFile();

        //   loadAllWeatherDataFromMqtt();
        // Check if data was loaded successfully
        if (_weatherData == null || _weatherData.isEmpty()) {
            System.out.println("Weather data is empty or null");
        } else {
            System.out.println("Weather data loaded successfully. Records: " + _weatherData.size());
            // Print a sample of the first row to verify data format
            if (_weatherData.size() > 0) {
                System.out.println("First row data: " + _weatherData.get(0));
            }
        }

        System.out.println("Starting simulation with data size: " + (_weatherData != null ? _weatherData.size() : 0));
        simulate();
        writeDataCsvNew();
    }

    /** public void runModel() throws IOException {
     System.out.println("Bắt đầu chạy mô hình tính toán sản lượng...");

     // 1. Không gọi loadAllWeatherDataFromMqtt() ở đây nữa vì Service đã tự chạy ngầm rồi.

     // 2. Kiểm tra xem luồng MQTT đã hứng được dòng dữ liệu nào chưa
     if (_weatherData == null || _weatherData.size() < 2) {
     System.out.println("⚠️ Chưa có đủ dữ liệu thời tiết từ MQTT (cần ít nhất 2 dòng) để chạy mô phỏng. Vui lòng đợi thêm...");
     return; // Dừng chạy mô hình nếu chưa có data
     }

     System.out.println("✅ Dữ liệu thời tiết hiện tại: " + _weatherData.size() + " records.");

     // 3. Tiến hành tính toán
     simulate();

     // 4. Ghi kết quả ra CSV
     writeDataCsvNew();
     System.out.println("✅ Chạy mô hình và xuất file kết quả thành công!");
     }
     */
    public void ode2InitModel(Double startTime, Double endTime) {
    }

    public List<Double> ode2initValuesTime0() {
        List<Double> yi = new ArrayList<>();
        for (int index = 0; index < 9 + _nsl * 5; ++index) {
            yi.add(0.0);
        }

        List<Double> iTheta = new ArrayList<>();
        for (int index = 0; index < _nsl; ++index) {
            iTheta.add(_iTheta + index * _thg);
        }

        List<Double> iNcont = new ArrayList<>();
        iNcont.add(39.830);
        iNcont.add(10.105);
        iNcont.add(16.050);
        iNcont.add(8.0);
        iNcont.add(8.0);
        for (int index = 5; index < 15; ++index) {
            iNcont.add(0.0);
        }

        double iNRT = 6.0;
        yi.set(1, _cuttingDryMass);
        yi.set(6, _cuttingDryMass);

        yi.set(9 + _nsl, iNRT);

        for (int i = 0; i < _nsl; ++i) {
            yi.set(9 + 2 * _nsl + i, iTheta.get(i));
            yi.set(9 + 3 * _nsl + i, iNcont.get(i) * this.fertilizationLevel / 100);
            yi.set(9 + 4 * _nsl + i, _cuttingDryMass * 30.0 / _nsl);
        }

        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);
        yi.add(0.0);

        return yi;
    }

    private void writeDataCsvNew() {
        String csvFilePath = "resultWeatherThaiLan.csv";
        try {
            FileWriter writer = new FileWriter(csvFilePath);
            CSVWriter csvWriter = new CSVWriter(writer);
            List<String[]> data = new ArrayList<>();
            data.add(new String[]{"day", "Yeild", "irr", "LeafArea", "LabieCarbon"});


            for (int i = 1; i < _results.get(0).size(); i++) {
                int doy = (int) Math.ceil(_results.get(8).get(i));

                int year = 2023 + (doy - 1) / 365;
                int dayOfYear = (doy - 1) % 365 + 1;

                LocalDate date = LocalDate.ofYearDay(year, dayOfYear);

                data.add(new String[]{
                        String.valueOf(date),
                        String.valueOf(_results.get(0).get(i)),
                        String.valueOf(_results.get(2).get(i)), // convert from mm to m^3/ha.
                        String.valueOf(_results.get(3).get(i)),
                        String.valueOf(_results.get(4).get(i))
                });
            }
            csvWriter.writeAll(data);
            csvWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void writeDataToCsvFile() {
        String csvFilePath = "irrigation_data1.csv";
        try {
            FileWriter writer = new FileWriter(csvFilePath);
            CSVWriter csvWriter = new CSVWriter(writer);
            List<String[]> data = new ArrayList<>();
            data.add(new String[]{"day", "Yeild", "irr"});
            for (int i = 0; i < _results.get(0).size(); i++) {
                data.add(new String[]{String.valueOf(i), String.valueOf(_results.get(0).get(i)), String.valueOf(_results.get(2).get(i))});
            }
            csvWriter.writeAll(data);
            csvWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // calulator models
//    public void simulate() {
//        _iStart = Double.parseDouble(_weatherData.get(1).get(_iDOY).toString());
//        _iend = Double.parseDouble(_weatherData.get(_weatherData.size() - 1).get(_iDOY).toString());
//        _autoIrrigateTime = -1;
//        //initialize to start simulate
//        List<Double> w = ode2initValues();
//        // _results = new ArrayList<>();
//        for (int i = 0; i < 10; i++) {
//            _results.add(new ArrayList<>());
//        }
//        for (int i = 2; i < _weatherData.size() - 1; i++) {
//            if (Double.parseDouble(_weatherData.get(i).get(1).toString()) > 277)
//                continue;
//            //get weather data
//            List<Double> wd = new ArrayList<>(); //weatherData
//            double rain = Double.parseDouble(_weatherData.get(i).get(_iRain).toString());
//            wd.add(rain); //wd[0]
//            double tempC = Double.parseDouble(_weatherData.get(i).get(_iTemp).toString());
//            wd.add(tempC); //wd[1]
//            double radiation = 24 * Double.parseDouble(_weatherData.get(i).get(_iRadiation).toString());
//            wd.add(2.5 * radiation); // ppfd = 2.5 * radiation, wd[2], photosynthetic photon flux density
//            double relativeHumidity = Double.parseDouble(_weatherData.get(i).get(_iRH).toString());
//            double wind = Double.parseDouble(_weatherData.get(i).get(_iWind).toString());
//            double doy = Double.parseDouble(_weatherData.get(i).get(1).toString());
//            double et0 = hourlyET(
//                    tempC,
//                    radiation,
//                    relativeHumidity,
//                    wind,
//                    doy,
//                    Constant.latitude,
//                    Constant.longitude,
//                    Constant.elevation,
//                    Constant.longitude,
//                    Constant.height);
//            wd.add(et0); //wd[3]
//
//            wd.add(0.0); //for irrigation,wd[4]
//            //khoảng cách thời gian giữa 2 thời gian
//            double dt = doy == _iStart ? 1e-10 : (Double.parseDouble(_weatherData.get(i).get(_iDOY).toString()) - Double.parseDouble(_weatherData.get(i - 1).get(_iDOY).toString()));
//            wd.add(dt);
//            // wd.set(0, rain*24);
//            //do step
//            rk4Step(doy - 76, w, dt, wd);
//
//            //if the next time is in a next day
//            if ((Math.floor(Double.parseDouble(_weatherData.get(i + 1).get(_iDOY).toString())) - Math.floor(doy)) > 0) {
//                _results.get(0).add(w.get(3) * 10 / _APPi); //yield
//                _results.get(1).add(w.get(9 + 2 * _nsl)); //theta
//                _results.get(2).add(w.get(9 + 5 * _nsl)); //irrigation;
//
//                _results.get(3).add(w.get(4) / _APPi); //lai
//                _results.get(4).add(100.0 + 100.0 * w.get(8) / Math.max(1.0, w.get(0) + w.get(1) + w.get(2) + w.get(3))); //clab
//                _results.get(5).add(w.get(9 + 5 * _nsl + 5)); //photo
//                _results.get(6).add(w.get(9 + 3 * _nsl)); //topsoil ncont
//                int ri = 9 + 4 * _nsl;
//                final double Nopt = 45 * w.get(0) + 2 * w.get(3) + 20 * w.get(1) + 20 * w.get(2);
//                _results.get(7).add((w.subList(ri, ri + _nsl))
//                        .stream()
//                        .reduce((value, element) -> value + element)
//                        .orElse(0.0) / Math.max(1.0, Nopt)); //nupt
//                _results.get(8).add(doy); //doy
//                _results.get(9).add(wd.get(3));
//            }
//        }
//        writeDataCsvFile(_results);
//    }
    int _iwdRowNum = 1;
    final int _iDT = 1; //13;
    final int _iLat = 7; //5;
    final int _iLong = 8; //6;
    final int _iElev = 9; //7;
    final int _iHeight = 10; //4;

    //lấy giá trị có thời gian gần với t nhất trong mảng _weatherData
    public List<Double> getWeatherData(double t) {

        final int iDOY = _iDOY;
        final int iRadiation = 2;
        final int iTemp = 3;
        final int iRain = 4;
        final int iRH = 5;
        final int iWind = 6;


        if (_iwdRowNum < 0) _iwdRowNum = 0;
        if (_iwdRowNum >= _weatherData.size()) _iwdRowNum = _weatherData.size() - 1;

        // Tìm _iwdRowNum sao cho doy <= t < nextDoy
        double doy = Double.parseDouble(_weatherData.get(_iwdRowNum).get(iDOY).toString());
        double nextDoy = (_iwdRowNum + 1 < _weatherData.size())
                ? Double.parseDouble(_weatherData.get(_iwdRowNum + 1).get(iDOY).toString())
                : doy + 1.0;

        // tìm dữ liệu trong _weatherData gần với t nhất
        while (t >= nextDoy && _iwdRowNum + 1 < _weatherData.size()) {
            ++_iwdRowNum;
            doy = Double.parseDouble(_weatherData.get(_iwdRowNum).get(iDOY).toString());
            nextDoy = (_iwdRowNum + 1 < _weatherData.size())
                    ? Double.parseDouble(_weatherData.get(_iwdRowNum + 1).get(iDOY).toString())
                    : doy + 1.0;
        }

        while (t < doy && _iwdRowNum > 0) {
            --_iwdRowNum;
            doy = Double.parseDouble(_weatherData.get(_iwdRowNum).get(iDOY).toString());
            nextDoy = (_iwdRowNum + 1 < _weatherData.size())
                    ? Double.parseDouble(_weatherData.get(_iwdRowNum + 1).get(iDOY).toString())
                    : doy + 1.0;
        }

        final int n = _iwdRowNum;

        // tính dt bằng hiệu giữa nextDoy và doy
        double dt = Math.max(1e-6, nextDoy - doy);

        // parse dữ liệu thời tiết
        double rain = Double.parseDouble(_weatherData.get(n).get(iRain).toString());
        double temp = Double.parseDouble(_weatherData.get(n).get(iTemp).toString());
        double radiation = Double.parseDouble(_weatherData.get(n).get(iRadiation).toString());
        double relativeHumidity = Double.parseDouble(_weatherData.get(n).get(iRH).toString());
        double wind = Double.parseDouble(_weatherData.get(n).get(iWind).toString());

        // tính ppfd
        double ppfd = radiation * 2.15;
        double et0 = 24 * hourlyET(temp, radiation, relativeHumidity, wind, doy, 21.0075, 105.5416, 16, 105.5416, 2.5);

        List<Double> YR = new ArrayList<>();
        YR.add(rain);       // wd[0]
        YR.add(temp);       // wd[1]
        YR.add(ppfd);       // wd[2]
        YR.add(et0);        // wd[3]
        YR.add(0.0);        // wd[4] irrigation
        YR.add(dt);         // wd[5] dt
        YR.add((double) n); // wd[6] row index

        return YR;
    }


    int pdt = 1;
    int _printSize = 366;
    List<Double> _printTime = new ArrayList<>(Collections.nCopies(_printSize, -1000.0));

//    public void setDonViThaiLan() {
//        // Doy,Rain,dt,Temp,Radiation,Relative Humidity,Wind,lat,long,elev
//        _iDOY = 0;
//        _iRadiation = 4;
//        _iRain = 1;
//        _iRH = 5;
//        _iTemp = 3;
//        _iWind = 6;
//    }

    public void simulate() {
        _iStart = Double.parseDouble(_weatherData.get(1).get(_iDOY).toString());
        _iend = Double.parseDouble(_weatherData.get(_weatherData.size() - 2).get(_iDOY).toString());
        _autoIrrigateTime = -1;
        // --- THÊM: Xóa dữ liệu cũ trước khi chạy ---
        _equivData.clear();
        caculateFcthresholdAndIrrigationRate();
        double t = _iStart;
        List<Double> w = ode2initValuesTime0(); //initialize to start simulate
        double dt = (double) 60 / (60 * 24); // khoang cach 1 h
        int ps = min(_printSize, (int) ceil((_iend - _iStart) / pdt));
        List<Double> ptime = new ArrayList<>();
        for (int index = 0; index < ps; ++index) {
            ptime.add(_iStart + (double) index * pdt);
        }
        for (int i = 0; i <= 8; i++) {
            List<Double> innerList = new ArrayList<>(Collections.nCopies(ptime.size(), 0.0));
            _results.add(innerList);
        }
        t = _iStart;
        for (int i = 0; i < ptime.size(); ++i) {
            // Forward simulation
            List<Double> wd = getWeatherData(t);
            double tw = t + wd.get(5);
            while (t < ptime.get(i) - 0.5 * dt) {
                double wddt = max(1e-10, min(min(dt, wd.get(5)), ptime.get(i) - t));
                // Do step
                // --- BẮT ĐẦU LOGIC ĐIỀU KHIỂN (CONTROLLER) ---

                // 1. "Sense": Lấy độ ẩm hiện tại (Sử dụng biến đã capture hoặc hàm tính riêng)
                // Lưu ý: Lúc bắt đầu w chứa trạng thái tại t.
                double currentTh = calculateCurrentThEquiv(w);

                // 2. "Think": Logic Sáng/Chiều + Threshold
                double currentHour = (t % 1.0) * 24.0;
                boolean isMorning = (currentHour >= 6.0 && currentHour < 7.0);
                boolean isAfternoon = (currentHour >= 16.0 && currentHour < 17.0);

                // Kiểm tra chặn lặp (đã tưới buổi này chưa?)
                boolean hasIrrigatedSession = (t - _autoIrrigateTime) < 0.5;

                if (!hasIrrigatedSession && (isMorning || isAfternoon) && currentTh < _fcthresold) {
                    _autoIrrigateTime = t; // BẬT BƠM
                    // --- THÊM: Ghi lịch sử tưới ---
                    // Chuyển đổi DOY (t) thành String thời gian để lưu trữ
                    int doyInt = (int) Math.floor(t);
                    int year = 2024 + (doyInt - 1) / 365;
                    int dayOfYear = (doyInt - 1) % 365 + 1;
                    LocalDate date = LocalDate.ofYearDay(year, dayOfYear);
                    double fractionDay = t - Math.floor(t);
                    int hours = (int) (fractionDay * 24);
                    int minutes = (int) ((fractionDay * 24 - hours) * 60);
                    String timeStr = String.format("%s %02d:%02d:00", date.toString(), hours, minutes);

                    HistoryIrrigation history = new HistoryIrrigation();
                    history.setTime(timeStr);
                    history.setUserName("Hệ thống tự động");
                    // Tính amount (l/m2) = IrrigationRate * Duration(day) * 1000
                    double amountVal = _IrrigationRate * (this.irrigationDuration / 24.0);
                    history.setAmount(amountVal);
                    history.setDuration(this.irrigationDuration * 60); // Đổi sang phút

                    this.listHistory.add(history);
                    // -------------------------------
                    System.out.println("Quyết định: BẬT TƯỚI lúc " + t);
                }

                // 3. "Act": Tính lượng nước sẽ bơm trong bước thời gian wddt này
                double irrigationFlux = 0.0;
                double irrigationDurationDays = _autoIrrigationDuration;

                if (t < _autoIrrigateTime + irrigationDurationDays) {
                    // Nếu đang trong thời gian tưới -> Cấp dòng chảy
                    irrigationFlux = _IrrigationRate;
                }

                // CẬP NHẬT INPUT CHO ODE2
                // wd.get(4) thường là cột mưa hoặc tưới từ file. Ta cộng thêm lượng tưới tự động vào.
                // Lưu ý: Cần reset lại giá trị gốc của wd trước khi cộng để tránh cộng dồn sai qua các vòng lặp
                double rainFromFile = 0.0; // Hoặc lấy từ file gốc nếu có
                wd.set(4, rainFromFile + irrigationFlux);

                // --- KẾT THÚC LOGIC ĐIỀU KHIỂN ---
                // --- THÊM: Lưu thEquiv vào danh sách tại thời điểm báo cáo ---
                List<Double> row = new ArrayList<>();
                row.add(t);               // Cột 1: Thời gian (DOY)
                row.add(currentTh);  // Cột 2: Độ ẩm tương đương
                _equivData.add(row);
                rk4Step(t - _iStart, w, wddt, wd);
                t += wddt;

                // Next row in weather data
                if (t > tw) {
                    wd = getWeatherData(t);
                    tw = t + wd.get(5);
                }
            }

            // Populate results
            _printTime.set(i, t);
            _results.get(0).set(i, w.get(3) * 10 / _APPi);
            _results.get(1).set(i, w.get(9 + 2 * _nsl));
            _results.get(2).set(i, w.get(9 + 5 * _nsl)); // irrigation
            _results.get(3).set(i, w.get(4) / _APPi);
            _results.get(4).set(i, 100.0 + 100.0 * w.get(8) / max(1.0, w.get(0) + w.get(1) + w.get(2) + w.get(3)));
            _results.get(5).set(i, w.get(9 + 5 * _nsl + 5));
            _results.get(6).set(i, w.get(9 + 3 * _nsl));
            // Thêm thời gian
            _results.get(8).set(i, t);

            int ri = 9 + 4 * _nsl;
            final double Nopt = 45 * w.get(0) + 2 * w.get(3) + 20 * w.get(1) + 20 * w.get(2);
            _results.get(7).add((w.subList(ri, ri + _nsl))
                    .stream()
                    .reduce((value, element) -> value + element)
                    .orElse(0.0) / Math.max(1.0, Nopt)); //nupt


        }
        writeDataToCsvFile();

        // --- THÊM: Xuất file CSV thEquiv ---
        writeDataEquiv();
    }

    public void rk4Step(double t, List<Double> y, double dt, List<Double> wd) {
        List<Double> yp = new ArrayList<>(y);

        List<Double> r1 = ode2(t, yp, wd);
        double t1 = t + 0.5 * dt;
        double t2 = t + dt;

        intStep(yp, r1, 0.5 * dt);
        List<Double> r2 = ode2(t1, yp, wd);

        for (int i = 0; i < y.size(); i++) {
            yp.set(i, y.get(i)); // reset
        }

        intStep(yp, r2, 0.5 * dt);
        List<Double> r3 = ode2(t1, yp, wd);

        for (int i = 0; i < y.size(); i++) {
            yp.set(i, y.get(i)); // reset
        }

        intStep(yp, r3, dt);
        List<Double> r4 = ode2(t2, yp, wd);

        for (int i = 0; i < r4.size(); i++) {
            r4.set(i, (r1.get(i) + 2 * (r2.get(i) + r3.get(i)) + r4.get(i)) / 6); // rk4
        }

        intStep(y, r4, dt); // final integration
    }


    public List<Double> ode2(double ct, List<Double> y, List<Double> wd) {

        // DEBUG: in giá trị wd để kiểm tra mapping cột
        try {
            System.out.println("DEBUG getWeatherData rowIndex = " + wd.get(6));
            System.out.println("DEBUG wd[0]=rain      : " + wd.get(0));
            System.out.println("DEBUG wd[1]=temp      : " + wd.get(1));
            System.out.println("DEBUG wd[2]=ppfd      : " + wd.get(2));
            System.out.println("DEBUG wd[3]=et0       : " + wd.get(3));
            System.out.println("DEBUG wd[4]=irrigation: " + wd.get(4));
            System.out.println("DEBUG wd[5]=dt        : " + wd.get(5));
        } catch (Exception e) {
            System.out.println("DEBUG: lỗi khi đọc wd: " + e.getMessage());
        }


        int cnt = -1;
        double LDM = y.get(++cnt); // Leaf Dry Mass (g)
        double SDM = y.get(++cnt); // Stem Dry Mass (g)
        double RDM = y.get(++cnt); // Root Dry Mass (g)
        double SRDM = y.get(++cnt); // Storage Root Dry Mass (g)
        double LA = y.get(++cnt); // Leaf Area (m2)
        System.out.printf("\n-------------------Input----------------------------\n");
        System.out.printf("LDM: " + LDM + "\n");
        System.out.printf("SDM: " + SDM + "\n");
        System.out.printf("RDM: " + RDM + "\n");
        System.out.printf("SRDM: " + SRDM + "\n");
        System.out.printf("LA: " + LA + "\n");

        double mDMl = y.get(++cnt); //intgrl("mDMl", 0, "mGRl");
        //double mDMld = y.get(7);//intgrl("mDMld", 0, "mGRld");
        double mDMs = y.get(++cnt); //intgrl("mDMs", cuttingDryMass, "mGRs");
        //double mDM = y.get(9);//intgrl("mDM", 0, "mGR");
        ++cnt; //double mDMsr = y.get(++cnt); //intgrl("mDMsr", 0, "mGRsr");
        //double TR = intgrl("TR", 0, "RR"); // Total Respiration (g C)
        double Clab = y.get(++cnt); // labile carbon pool
        ++cnt;
        List<Double> rlL = y.subList(cnt, cnt += _nsl); //Root length per layer (m)
        //double RL = sumList(RL_l); // Root length (m)

        List<Double> nrtL = y.subList(cnt, cnt += _nsl); //Root tips per layer
        double NRT = 0;
        for (double element : nrtL) {
            NRT += element;
        }
        List<Double> thetaL = y.subList(cnt, cnt += _nsl); //volumetric soil water content for each layer

        //double Ncont_l = intgrl("Ncont",[4.83+35, 10.105, 16.05]*_lvol*BD,"NcontR");// N-content in a soil layer (mg);
        List<Double> ncontL = y.subList(cnt, cnt += _nsl);
        List<Double> nuptL = y.subList(cnt, cnt += _nsl);
        double Nupt = 0;
        for (double element : nuptL) {
            Nupt += element;
        }


        double TDM = LDM + SDM + RDM + SRDM + Clab;
        double cDm = 0.43;
        double leafTemp = wd.get(1);
        double TSphot = lim((-0.832097717 + 0.124485738 * leafTemp - 0.002114081 * Math.pow(leafTemp, 2)), 0, 1);
        double TSshoot = lim((-1.5 + 0.125 * leafTemp), 0, 1) * lim((7.4 - 0.2 * leafTemp), 0, 1);
        double TSroot = 1.0;
        System.out.printf("\n-------------------Process---------------------------- \n");
        System.out.printf("TDM: " + TDM + "\n");
        System.out.printf("TSphot: " + TSphot + "\n");
        System.out.printf("TSshoot : " + TSshoot + "\n");
        System.out.printf("RDM: " + RDM + "\n");

        List<Double> krootL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            krootL.add(fKroot(thetaL.get(i), rlL.get(i)));
        }
        //sums up all elements.
        double Kroot = krootL.stream().mapToDouble(Double::doubleValue).sum();
        Kroot = Math.max(1e-8, Kroot);

        double thEquiv;
        if (Kroot > 1e-8) {
            double sumThetaKroot = 0.0;
            for (int i = 0; i < _nsl; ++i) {
                sumThetaKroot += thetaL.get(i) * krootL.get(i);
            }
            thEquiv = sumThetaKroot / Kroot;
        } else {
            thEquiv = thetaL.get(0);
        }

        double WStrans = fWstress(0.05, 0.5, thEquiv);
        double WSphot = fWstress(0.05, 0.3, thEquiv);
        System.out.printf("WStrans: " + WStrans + "\n");
        System.out.printf("WSphot: " + WSphot + "\n");

        double WSshoot = fWstress(0.2, 0.55, thEquiv);
        double WSroot = 1;
        double WSleafSenescence = 1.0 - fWstress(0.0, 0.2, thEquiv);

        // water in soil
        //irrigation either not (rained), or from file, or auto.
        // file/auto should switch on current date?
        double irrigation = this.autoIrrigation ? wd.get(4) : 0.0;

        double precipitation = this.scaleRain / 100 * wd.get(0) + irrigation;
        System.out.println("precipitation: " + precipitation + "\n");
        double ET0reference = wd.get(3);
        double ETrainFactor = (precipitation > 0) ? 1 : 0;
        double kdf = -0.47;
        double ll = Math.exp(kdf * LA / _APPi);
        //  double cropFactor = Math.max(1 - ll * 0.8, ETrainFactor);
        double cropFactor = 1 - ll * 0.8;
        double transpiration = cropFactor * ET0reference;
        double swfe = Math.pow(relTheta(thetaL.get(0)), 2.5);
        double actFactor = Math.max(ll * swfe, ETrainFactor);
        double evaporation = actFactor * ET0reference;

        double actualTranspiration = transpiration * WStrans;
        List<Double> wuptrL = multiplyListsWithConstant(krootL, actualTranspiration / Kroot);

        double drain = 0.0;
        List<Double> qFlow = new ArrayList<>(Collections.nCopies(_nsl + 1, 0.0));
        qFlow.set(0, (precipitation - evaporation) / (_lw * 1000.0));

        for (int i = 1; i < qFlow.size(); ++i) {
            double thdown = (i < _nsl)
                    ? thetaL.get(i)
                    : (_zerodrain)
                      ? thetaL.get(i - 1) + _thg
                      : _thm;
            qFlow.set(i, qFlow.get(i) +
                    (thetaL.get(i - 1) + _thg - thdown) * _rateFlow * (thetaL.get(i - 1) / _ths) +
                    4.0 * Math.max(thetaL.get(i - 1) - _ths, 0));
        }

        List<Double> dThetaDt = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            double dTheta = qFlow.get(i) - qFlow.get(i + 1) - wuptrL.get(i) / (_lw * 1000.0);
            dThetaDt.add(dTheta);
            if (Double.isNaN(dTheta)) {
                System.out.println("dThetaDt: " + dTheta + " qFlow: " + qFlow);
            }
        }

        drain = qFlow.get(_nsl) * _lw * 1000;
        // nutrient concentrations in the plant
        double Nopt = 45 * LDM + 7 * SRDM + 20 * SDM + 20 * RDM;
        double NuptLimiter = 1.0 - fNSstress(Nupt, 2.0 * Nopt, 3.0 * Nopt);
        List<Double> nuptrL = new ArrayList<>();
        for (int i = 0; i < _nsl; i++) {
            double nuptr = monod(ncontL.get(i) * _BD / (1000 * thetaL.get(i)),
                    NuptLimiter * rlL.get(i) * 0.8,
                    12.0 * 0.5);
            nuptrL.add(nuptr);
            if (Double.isNaN(nuptr)) {
                System.out.println("ncont_l=" + ncontL + " theta_l=" + thetaL);
            }
        }

        List<Double> ncontrL = new ArrayList<>(Collections.nCopies(_nsl, 0.0));
        List<Double> _NminR_l = new ArrayList<>();
        for (int d = 0; d < _nsl; d++) {
            double nminR = this.fertilizationLevel / 100.0 *
                    36.0 / (_lvol * _BD) /
                    Math.pow(d + 1, 2);
            _NminR_l.add(nminR);
        }

        for (int i = 0; i < _nsl; i++) {
            ncontrL.set(i, _NminR_l.get(i));
            ncontrL.set(i, ncontrL.get(i) - nuptrL.get(i) / (_BD * _lvol)); //mg/day/ (m3*kg/m3)
            double Nl = ncontL.get(i);
            double Nu = (i > 0) ? ncontL.get(i - 1) : -ncontL.get(i);
            double Nd = (i < (_nsl - 1)) ? ncontL.get(i + 1) : 0.0;
            ncontrL.set(i, ncontrL.get(i) + qFlow.get(i) * (Nu + Nl) / 2.0 - qFlow.get(i + 1) * (Nl + Nd) / 2.0);
        }

        double NSphot = (Nopt > 1e-3) ? fNSstress(Nupt, 0.7 * Nopt, Nopt) : 1.0;
        double NSshoot = (Nopt > 1e-3) ? fNSstress(Nupt, 0.7 * Nopt, 0.9 * Nopt) : 1.0;
        double NSroot = (Nopt > 1e-3) ? fNSstress(Nupt, 0.5 * Nopt, 0.7 * Nopt) : 1.0;
        double NSleafSenescence = (Nopt > 1.0) ? 1.0 - fNSstress(Nupt, 0.8 * Nopt, Nopt) : 0.0;

        // sink strength
        double mGRl = logistic(ct, 0.3, 70, 0, 0.9);
        double mGRld = logistic(ct, 0.0, 70.0 + _leafAge, 0.1, -0.90);
        double mGRs = logistic(ct, 0.2, 95, 0.219, 1.87) +
                logistic(ct, 0.0, 209, 0.219, 1.87 - 0.84);
        double mGRr = 0.02 + (0.2 + Math.exp(-0.8 * ct - 0.2)) * mGRl;
        double mGRsr = Math.min(7.08, Math.pow(Math.max(0.0, (ct - 32.3) * 0.02176), 2));
        double mDMr = 0.02 * ct + 1.25 + 0.25 * ct -
                1.25 * Math.exp(-0.8 * ct) * mGRl +
                (0.25 + Math.exp(-0.8 * ct)) * mDMl;

        double CSphot = getStress(Clab, TDM, 0.05, -9999.9, true);
        double CSshoota = getStress(Clab, TDM, -0.05, -9999.9, false);
        double CSshootl = lim((5 - LA / _APPi), 0, 1);
        double CSshoot = CSshoota * CSshootl;
        double CSroot = getStress(Clab, TDM, -0.03, -9999.9, false);
        double CSsrootl = getStress(Clab, TDM, -0.0, -9999.9, false);
        double CSsrooth = getStress(Clab, TDM, 0.01, 0.20, false);
        double starchRealloc = getStress(Clab, TDM, -0.2, -0.1, true) * -0.05 * SRDM;
        double CSsroot = CSsrootl + 2 * CSsrooth;
        double SFleaf = WSshoot * NSshoot * TSshoot * CSshootl;
        double SFstem = WSshoot * NSshoot * TSshoot * CSshoot;
        double SFroot = WSroot * NSroot * TSroot * CSroot;
        double SFsroot = CSsroot;

        double CsinkL = cDm * mGRl * SFleaf;
        double CsinkS = cDm * mGRs * SFstem;
        double CsinkR = cDm * mGRr * SFroot;
        double CsinkSR = cDm * mGRsr * SFsroot - starchRealloc;
        double Csink = CsinkL + CsinkS + CsinkR + CsinkSR;

        // biomass partitioning
        double a2l = CsinkL / Math.max(1e-10, Csink);
        double a2s = CsinkS / Math.max(1e-10, Csink);
        double a2r = CsinkR / Math.max(1e-10, Csink);
        double a2sr = CsinkSR / Math.max(1e-10, Csink);

        // carbon to growth
        double CFG = Csink;// carbon needed for growth (g C/day)
        // increase in plant dry Mass (g DM/day) not including labile carbon pool
        double IDM = Csink / cDm;
        double PPFD = wd.get(2);
        double SFphot = Math.min(Math.min(TSphot, WSphot), Math.min(NSphot, CSphot));
        double CFR = photoFixMean(PPFD, LA / _APPi, -0.47, 29.37 * SFphot, 0.05553, 0.90516);
        System.out.println("\n ----Carbon to growth-----");
        System.out.println("CFG = " + CFG);
        System.out.println("IDM = " + IDM);
        System.out.println("PPFD = " + PPFD);
        System.out.println("SFphot = " + SFphot);
        System.out.println("CFR = " + CFR);

        //photosynthesis
        double SDMR = a2s * IDM;
        double SRDMR = a2sr * IDM;
        double SLA = fSLA(ct);
        double LDRstress = WSleafSenescence * NSleafSenescence * LDM * -1.0;
        double LDRage = mGRld * ((mDMl > 0) ? LDM / mDMl : 1.0);
        if (LDRstress > 1e-10 || LDRage > 1e-10) {
            throw new AssertionError("LDRstress: " + LDRstress + " LDRage: " + LDRage);
        }
        double LDRm = Math.max(-LDM, LDRstress + LDRage);
        double LDRa = Math.max(-LA, fSLA(Math.max(0.0, ct - _leafAge)) * LDRm);
        double LAeR = SLA * a2l * IDM + LDRa;// Leaf Area expansion Rate (m2/day)
        double LDMR = a2l * IDM + LDRm;// leaf growth rate (g/day) - death rate (g/day)

        double RDMR = a2r * IDM; // fine root growth rate (g/day)
        double RLR = _SRL * RDMR;
        List<Double> rlrL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            double ln1 = RLR * nrtL.get(i) / NRT;
            rlrL.add(ln1);
        }
        double ln0 = 0.0;
        List<Double> nrtrL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            double ln1 = rlrL.get(i);
            nrtrL.add(ln1 * 60.0 + Math.max(0, (ln0 - ln1 - 6.0 * _lw) * 10.0 / _lw));
            ln0 = ln1;
        }

        double mRR = 0.003 * RDM + 0.0002 * SRDM + 0.003 * LDM + 0.0002 * SDM;
        double gRR = 1.8 * RDMR + 0.2 * SRDMR + 1.8 * (LDMR - LDRm) + 0.4 * SDMR;
        double RR = mRR + gRR;

        double ClabR = (CFR - CFG - RR) / cDm;
        cnt = -1;
        List<Double> YR = new ArrayList<>();
        YR.add(++cnt, LDMR);
        YR.add(++cnt, SDMR);
        YR.add(++cnt, RDMR);
        YR.add(++cnt, SRDMR);
        YR.add(++cnt, LAeR);
        YR.add(++cnt, mGRl);
        YR.add(++cnt, mGRs);
        YR.add(++cnt, (double) mGRsr); // Using (double) to convert to double
        YR.add(++cnt, ClabR);
        YR.addAll(rlrL);
        YR.addAll(nrtrL);
        YR.addAll(dThetaDt);
        YR.addAll(ncontrL);
        YR.addAll(nuptrL);

        YR.add((double) irrigation); // Just for reporting amount of water needed
        YR.add(wd.get(0)); // rain
        YR.add((double) actualTranspiration); // Just for reporting amount of water needed
        YR.add(evaporation);
        YR.add(drain);
        YR.add(CFR);
        YR.add(PPFD);

        return YR;
    }


    public double fWstress(double minv, double maxv, double the) {
        double s = 1 / (maxv - minv);
        double i = -1 * minv * s;
        return lim((i + s * relTheta(the)), 0, 1);
    }


    public double fNSstress(double upt, double low, double high) {
        double rr = (upt - low) / (high - low);
        return lim(rr, 0, 1);
    }

    public List<Double> ode2initValues() {
        List<Double> yi = new ArrayList<>();
        // Dữ liệu mới
        Double[] dataToAdd = {
                59.9936, 60.6656, 18.267223323508567, 93.0756,
                2.4665560462821263, 91.79400000000325, 202.84134540781776, 277.46639346799145,
                31.685022446633873, 246.0908445789579, 206.014020579492, 150.50254098663453,
                84.81732279105968, 24.99698068055664, 14771.450674737114, 12376.546166518712,
                9053.470870979236, 5109.872440430796, 1509.2600113651777, 0.2381801339197489,
                0.23832060394348634, 0.2528704670241301, 0.27385732934211704, 0.2953060649096794,
                0.5699703070593461, 0.45975876345694205, 3.0590914791309287, 1.0999841454865997,
                6.4442547370882055, 7888.635585812703, 6728.453739220788, 5256.491465187306,
                2797.305390900457, 1209.871412709246, 0.0, 258.30194219999834,
                440.58641444762077, 197.81995900125494, 55.96304865702387, 590.9555448821034,
                44512.398073766424
        };

        for (Double value : dataToAdd) {
            yi.add(value);
        }
        return yi;
    }


    public double hourlyET(
            final double tempC,
            final double radiation,
            final double relativeHumidity,
            final double wind,
            final double doy,
            final double latitude,
            final double longitude,
            final double elevation,
            final double longZ,
            final double height) {

        final double pi = Math.PI;
        final double hours = (doy % 1) * 24;
        final double tempK = tempC + 273.16;

        final double Rs = radiation * 3600 / 1e+06;
        final double P = 101.3 *
                Math.pow((293 - 0.0065 * elevation) / 293, 5.256);
        final double psi = 0.000665 * P;

        final double Delta = 2503 *
                Math.exp((17.27 * tempC) / (tempC + 237.3)) /
                Math.pow(tempC + 237.3, 2);
        final double eaSat = 0.61078 *
                Math.exp((17.269 * tempC) / (tempC + 237.3));
        final double ea = (relativeHumidity / 100) * eaSat;

        final double DPV = eaSat - ea;
        final double dr = 1 + 0.033 * Math.cos(2 * pi * doy / 365.0);
        final double delta = 0.409 *
                Math.sin(2 * pi * doy / 365.0 - 1.39);
        final double phi = latitude * (pi / 180);
        final double b = 2.0 * pi * (doy - 81.0) / 364.0;

        final double Sc = 0.1645 * Math.sin(2 * b) - 0.1255 * Math.cos(b) - 0.025 * Math.sin(b);
        final double hourAngle = (pi / 12) *
                ((hours + 0.06667 * (longitude * pi / 180.0 - longZ * pi / 180.0) + Sc) - 12.0);
        final double w1 = hourAngle - ((pi) / 24);
        final double w2 = hourAngle + ((pi) / 24);
        final double hourAngleS = Math.acos(-Math.tan(phi) * Math.tan(delta));
        final double w1c = (w1 < -hourAngleS) ? -hourAngleS : (w1 > hourAngleS) ? hourAngleS : (w1 > w2) ? w2 : w1;
        final double w2c = (w2 < -hourAngleS) ? -hourAngleS : (w2 > hourAngleS) ? hourAngleS : w2;

        final double Beta = Math.asin((Math.sin(phi) * Math.sin(delta) + Math.cos(phi) * Math.cos(delta) * Math.cos(hourAngle)));

        final double Ra = (Beta <= 0) ? 1e-45 : ((12 / pi) * 4.92 * dr) *
                                                (((w2c - w1c) * Math.sin(phi) * Math.sin(delta)) +
                                                 (Math.cos(phi) * Math.cos(delta) * (Math.sin(w2) - Math.sin(w1))));

        final double Rso = (0.75 + 2e-05 * elevation) * Ra;

        final double RsRso = (Rs / Rso <= 0.3) ? 0.0 : (Rs / Rso >= 1) ? 1.0 : Rs / Rso;
        final double fcd = (1.35 * RsRso - 0.35 <= 0.05) ? 0.05 : (1.35 * RsRso - 0.35 < 1) ? 1.35 * RsRso - 0.35 : 1;

        final double Rna = ((1 - 0.23) * Rs) -
                (2.042e-10 * fcd * (0.34 - 0.14 * Math.sqrt(ea)) * Math.pow(tempK, 4));

        final double Ghr = (Rna > 0) ? 0.04 : 0.2;
        // G for hourly depend on Rna (or Rn in EThourly)
        final double Gday = Rna * Ghr;
        final double wind2 = wind * (4.87 / (Math.log(67.8 * height - 5.42)));
        final double windf = (radiation > 1e-6) ? 0.25 : 1.7;

        final double EThourly = ((0.408 * Delta * (Rna - Gday)) +
                (psi * (66 / tempK) * wind2 * (DPV))) /
                (Delta + (psi * (1 + (windf * wind2))));

        return EThourly;
    }

    void intStep(final List<Double> y, final List<Double> r, final double dt) {
        assert (y.size() == r.size());
        for (int i = 0; i < y.size(); ++i) {
            y.set(i, y.get(i) + dt * r.get(i));
        }
    }

    /**
     * Hàm phụ trợ: Tính thEquiv từ biến trạng thái w (hoặc y) hiện tại
     */

    public double calculateCurrentThEquiv(List<Double> w) {
        // Dựa trên cấu trúc chỉ số trong hàm ode2
        // rlL bắt đầu từ index 9
        // thetaL bắt đầu từ index 9 + 2*_nsl (tức là 19)

        int rlL_startIndex = 9;
        int thetaL_startIndex = 9 + 2 * _nsl;

        List<Double> rlL = w.subList(rlL_startIndex, rlL_startIndex + _nsl);
        List<Double> thetaL = w.subList(thetaL_startIndex, thetaL_startIndex + _nsl);

        // Logic tính toán giống hệt trong ode2
        List<Double> krootL = new ArrayList<>();
        for (int i = 0; i < _nsl; ++i) {
            krootL.add(fKroot(thetaL.get(i), rlL.get(i)));
        }

        double Kroot = krootL.stream().mapToDouble(Double::doubleValue).sum();
        Kroot = Math.max(1e-8, Kroot);

        double thEquiv;
        if (Kroot > 1e-8) {
            double sumThetaKroot = 0.0;
            for (int i = 0; i < _nsl; ++i) {
                sumThetaKroot += thetaL.get(i) * krootL.get(i);
            }
            thEquiv = sumThetaKroot / Kroot;
        } else {
            thEquiv = thetaL.get(0);
        }

        return thEquiv;
    }

    /**
     * Ghi file CSV cho dữ liệu thEquiv
     */
    public void writeDataEquiv() {
        String csvFilePath = "thEquiv_tracking.csv"; // Tên file output
        System.out.println("Đang ghi file thEquiv...");

        try {
            FileWriter writer = new FileWriter(csvFilePath);
            CSVWriter csvWriter = new CSVWriter(writer);
            List<String[]> data = new ArrayList<>();

            // Header của file CSV
            data.add(new String[]{"Timestamp", "Day_Of_Year", "thEquiv_Value"});

            for (List<Double> row : _equivData) {
                double doyFull = row.get(0);
                double val = row.get(1);

                // 1. Xử lý ngày tháng năm (Giả sử năm 2023)
                int doyInt = (int) Math.floor(doyFull);
                int year = 2023 + (doyInt - 1) / 365;
                int dayOfYear = (doyInt - 1) % 365 + 1;
                LocalDate date = LocalDate.ofYearDay(year, dayOfYear);

                // 2. Xử lý giờ phút (phần thập phân của DOY)
                double fractionDay = doyFull - Math.floor(doyFull);
                int totalSeconds = (int) (fractionDay * 24 * 3600);
                int hours = totalSeconds / 3600;
                int minutes = (totalSeconds % 3600) / 60;
                int seconds = totalSeconds % 60;

                // Format chuỗi thời gian: YYYY-MM-DD HH:mm:ss
                String timeString = String.format("%s %02d:%02d:%02d", date.toString(), hours, minutes, seconds);

                // Thêm vào dòng dữ liệu
                data.add(new String[]{
                        timeString,                 // Cột 1: Ngày giờ đọc được
                        String.format("%.4f", doyFull), // Cột 2: DOY gốc
                        String.valueOf(val)         // Cột 3: Giá trị thEquiv
                });
            }

            csvWriter.writeAll(data);
            csvWriter.close();
            System.out.println("Đã xuất file " + csvFilePath + " thành công!");
            System.out.println(this.toString());
            System.out.println(fieldName);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void caculateFcthresholdAndIrrigationRate() {
        // cacular fcthresold
        _fcthresold = this.fieldCapacity;
        _fcthresold *= (_ths - _thr) / 100;
        _fcthresold += _thr;

        // cacular auto Irrigation Duration
        _autoIrrigationDuration = this.irrigationDuration / 24; // fix cung tuoi trong 2h/lan tuoi
        //convert from hour to day
        double dhr = this.dripRate;// l/hour
        double dhd = this.distanceBetweenHole;//cm
        double dld = this.distanceBetweenRow;//cm
        _IrrigationRate = dhr * 24.0 / (dhd * dld / 10000.0);//mm

    }

    /**
     * Hàm này sẽ được gọi sau khi mô phỏng kết thúc để tải lịch sử tưới lên Firebase
     */
    public void uploadHistoryToFirebase() {
        if (this.listHistory.isEmpty()) return;

        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("user")
                .child(this.fieldName)
                .child("historyIrrigation");

        // 2. Xóa toàn bộ dữ liệu cũ tại nút này
        ref.removeValue((error, reference) -> {
            if (error == null) {
                System.out.println("Đã xóa lịch sử cũ thành công. Đang cập nhật dữ liệu mới...");

                // 3. Nếu có dữ liệu mới trong listHistory thì tiến hành upload
                if (this.listHistory != null && !this.listHistory.isEmpty()) {
                    for (HistoryIrrigation history : this.listHistory) {
                        // Dùng push() để tạo ID duy nhất cho mỗi bản ghi mới
                        ref.push().setValueAsync(history);
                    }
                    System.out.println("Đã cập nhật " + this.listHistory.size() + " bản ghi mới.");
                }
            } else {
                System.err.println("Lỗi khi xóa dữ liệu cũ: " + error.getMessage());
            }
        });
    }
    public static void resetDoyStaticFields() {
        previousDoy = -1;
        doyOffset = 0;
    }
    // =========================================================================
    // CÁC HÀM QUẢN LÝ TRẠNG THÁI MÔ PHỎNG (LƯU/ĐỌC TỪ CSV)
    // =========================================================================

    /**
     * Hàm phụ trợ: Lấy tên file lưu trạng thái động theo tên cánh đồng
     */
    private String getStateFilePath() {
        // Ví dụ: Nếu fieldName là "Khu_A", file sẽ là "Khu_A_simulation_state.csv"
        return (this.fieldName != null ? this.fieldName : "default_field") + "_simulation_state.csv";
    }

    /**
     * 1. ĐỌC TRẠNG THÁI (Load State)
     * Đọc dòng cuối cùng của file CSV để khôi phục mảng w và thời gian DOY
     * Trả về List<Double> với: index 0 là DOY, từ index 1 trở đi là các giá trị của mảng w
     */
    public List<Double> loadLastState() {
        File file = new File(getStateFilePath());
        if (!file.exists()) {
            System.out.println("⚠️ Chưa có file trạng thái cho [" + this.fieldName + "]. Sẽ bắt đầu tính toán từ đầu.");
            return null; // Trả về null để ra hiệu khởi tạo lại từ đầu
        }

        List<Double> lastState = new ArrayList<>();
        // Sử dụng các thư viện CSVParser đã import sẵn ở đầu file Field.java
        try (FileReader fileReader = new FileReader(file, StandardCharsets.UTF_8);
             CSVParser csvParser = new CSVParser(fileReader, CSVFormat.DEFAULT)) {

            CSVRecord lastRecord = null;
            // Đọc lướt qua để lấy dòng cuối cùng (dòng mới nhất)
            for (CSVRecord record : csvParser) {
                lastRecord = record;
            }

            if (lastRecord != null) {
                for (String value : lastRecord) {
                    lastState.add(Double.parseDouble(value.trim()));
                }
            }
        } catch (Exception e) {
            System.err.println("❌ Lỗi khi đọc file state CSV của field " + this.fieldName + ": " + e.getMessage());
            return null;
        }
        return lastState.isEmpty() ? null : lastState;
    }

    /**
     * 2. GHI TRẠNG THÁI (Save State)
     * Ghi nối tiếp (append) trạng thái hiện tại của mô hình vào file CSV
     */
    public void saveState(double currentDoy, List<Double> w) {
        // FileWriter tham số thứ 2 là 'true' -> Chế độ Append (Ghi nối thêm vào cuối file)
        try (FileWriter writer = new FileWriter(getStateFilePath(), true);
             CSVWriter csvWriter = new CSVWriter(writer)) {

            // Chuyển DOY và mảng w thành mảng String để ghi xuống CSV
            String[] dataRecord = new String[w.size() + 1];
            dataRecord[0] = String.valueOf(currentDoy); // Cột đầu tiên là mốc thời gian DOY

            for (int i = 0; i < w.size(); i++) {
                dataRecord[i + 1] = String.valueOf(w.get(i));
            }

            csvWriter.writeNext(dataRecord);
            System.out.println("💾 Đã lưu thành công trạng thái w tại DOY = " + currentDoy + " cho Field [" + this.fieldName + "] vào CSV.");

        } catch (IOException e) {
            System.err.println("❌ Lỗi khi ghi file state CSV cho Field [" + this.fieldName + "]: " + e.getMessage());
        }
    }
}
