package com.example.demo.firebase;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.FileInputStream;

@Configuration
public class FirebaseInitialization {

    @Bean
    public FirebaseApp firebaseApp() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String filePath = os.contains("win")
                    ? "D:\\DATN\\serviceAccountKey.json"
                    : "/opt/cassava/serviceAccountKey.json";

            File file = new File(filePath);
            if (!file.exists()) {
                System.out.println("[INFO] Firebase service account key not found at " + filePath
                        + " — skipping Firebase init.");
                return null;
            }

            if (!FirebaseApp.getApps().isEmpty()) {
                System.out.println("[DEBUG] FirebaseApp already initialized.");
                return FirebaseApp.getInstance();
            }

            System.out.println("[DEBUG] Initializing Firebase with: " + filePath);
            FileInputStream serviceAccount = new FileInputStream(filePath);

            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .setDatabaseUrl("https://directionproject-1e798-default-rtdb.firebaseio.com")
                    .build();

            return FirebaseApp.initializeApp(options);
        } catch (Exception e) {
            System.err.println("[WARN] Firebase init failed, continuing without Firebase: " + e.getMessage());
            return null;
        }
    }
}
