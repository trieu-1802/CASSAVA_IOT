package com.example.demo.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.servlet.ServletContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Paths;

@Configuration
public class FirebaseInitialization {
    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        //FileInputStream serviceAccount = null;
        String os = System.getProperty("os.name").toLowerCase();

        String filePath;
        if (os.contains("win")) {
            filePath = "D:\\DATN\\test\\serviceAccountKey.json";// Đường dẫn cho Windows
        } else {
            filePath = "/home/kien/Kin/LAB_IOT/version2/CASSAVA_IOT/cassavaBE/serviceAccountKey.json";// Đường dẫn cho Ubuntu hoặc các hệ điều hành Unix-like khác
        }

        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("Không tìm thấy file serviceAccountKey.json tại: " + filePath);
        }

        System.out.println("📌 [DEBUG] Đang sử dụng file: " + filePath);
        FileInputStream serviceAccount = new FileInputStream(filePath);

        //serviceAccount = new FileInputStream(filePath);
            //serviceAccount = new FileInputStream("./serviceAccountKey.json");
        FirebaseOptions options = new FirebaseOptions.Builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .setDatabaseUrl("https://directionproject-1e798-default-rtdb.firebaseio.com")
                .build();

        return FirebaseApp.initializeApp(options);
    }
}
