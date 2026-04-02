package com.example.demo.controller;

import com.example.demo.entity.ChartData;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
public class ChartController {

    // Cho phép tất cả các nguồn (frontend) truy cập API này để tránh lỗi CORS
    @CrossOrigin(origins = "*")
    @GetMapping("/api/chart-data")
    public List<ChartData> getChartData() {
        List<ChartData> dataList = new ArrayList<>();

        // Đường dẫn file CSV (File này phải nằm ở thư mục gốc của project)
        String filePath = "thEquiv_tracking.csv";

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;

            // Đọc bỏ dòng tiêu đề đầu tiên
            br.readLine();

            while ((line = br.readLine()) != null) {
                // Tách các giá trị bằng dấu phẩy
                String[] values = line.split(",");

                // File CSV mới sẽ có 3 cột (0 đến 2)

                    try {
                        // Cột 0: Ngày (Xóa dấu ngoặc kép và khoảng trắng thừa)
                        String day = values[0].replace("\"", "").trim();


                        // Cột 8: Theta Equiv
                        String thetaEquivStr = values[2].replace("\"", "").trim();
                        double thetaEquiv = Double.parseDouble(thetaEquivStr);

                        // Thêm vào danh sách
                        dataList.add(new ChartData(day, thetaEquiv));

                    } catch (NumberFormatException e) {
                        // Bỏ qua các dòng bị lỗi định dạng số (hoặc dòng trống)
                        System.err.println("Lỗi đọc dòng: " + line);
                    }

            }
        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("Lỗi: Không tìm thấy file " + filePath + ". Hãy chạy mô hình (simulate) trước.");
        }

        return dataList;
    }
}