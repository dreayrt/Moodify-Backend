package com.laphuth.moodify.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;

/**
 * Cấu hình khởi tạo Firebase Admin SDK phục vụ Push Notification (FCM)
 * tới toàn bộ thiết bị khi Admin gửi thông báo.
 * 
 * Nếu chưa có file `firebase-service-account.json`, hệ thống sẽ ghi log cảnh báo
 * êm ái mà không làm gián đoạn hay crash tiến trình khởi động của Backend.
 */
@Configuration
public class FirebaseConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseConfig.class);

    private final ResourceLoader resourceLoader;

    @Value("${firebase.service-account-path:classpath:firebase-service-account.json}")
    private String serviceAccountPath;

    public FirebaseConfig(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void initFirebase() {
        if (!FirebaseApp.getApps().isEmpty()) {
            return;
        }

        try {
            Resource resource = resourceLoader.getResource(serviceAccountPath);
            if (!resource.exists()) {
                log.info("Chưa tìm thấy file cấu hình Firebase [{}] trong Backend. Thông báo đẩy FCM sẽ tạm dừng cho đến khi bạn thêm file này.", serviceAccountPath);
                return;
            }

            try (InputStream is = resource.getInputStream()) {
                FirebaseOptions options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(is))
                        .build();
                FirebaseApp.initializeApp(options);
                log.info("Đã khởi tạo Firebase Admin SDK thành công từ [{}]!", serviceAccountPath);
            }
        } catch (Exception e) {
            log.warn("Không thể khởi tạo Firebase Admin SDK: {}", e.getMessage());
        }
    }
}
