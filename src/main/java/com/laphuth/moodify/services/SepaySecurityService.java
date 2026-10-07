package com.laphuth.moodify.services;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Service xác thực chữ ký HMAC-SHA256 từ SePay Webhook.
 *
 * Khi SePay gọi webhook, nó sẽ:
 *   1. Dùng Secret Key để băm toàn bộ request body (JSON raw) bằng thuật toán HMAC-SHA256.
 *   2. Gửi kết quả dưới dạng chuỗi hex trong header "x-sepay-signature".
 *
 * Backend nhận raw body + header signature, tự tính lại HMAC rồi so sánh.
 * Nếu khớp → request đến từ SePay và nội dung chưa bị giả mạo/chỉnh sửa.
 */
@Service
public class SepaySecurityService {

    @Value("${sepay.secret-key:${SEPAY_SECRET_KEY:}}")
    private String secretKey;

    /**
     * Xác minh chữ ký HMAC-SHA256 theo chuẩn SePay Webhook.
     *
     * Chuẩn ký của SePay:
     *   1. Data ký: "{timestamp}.{raw_body}" (nếu có timestamp) hoặc raw_body
     *   2. Header signature: có thể có tiền tố "sha256="
     *
     * @param rawPayload      Chuỗi JSON gốc (raw body) mà SePay gửi tới.
     * @param signatureHeader Giá trị header "x-sepay-signature" từ SePay.
     * @param timestampHeader Giá trị header "x-sepay-timestamp" từ SePay.
     * @return true nếu chữ ký hợp lệ.
     */
    public boolean verifySignature(String rawPayload, String signatureHeader, String timestampHeader) {
        // Nếu chưa cấu hình secret key → bỏ qua kiểm tra (dev mode)
        if (secretKey == null || secretKey.isBlank()) {
            System.out.println("[SePay] CẢNH BÁO: Chưa cấu hình SEPAY_SECRET_KEY, bỏ qua xác thực HMAC.");
            return true;
        }

        if (signatureHeader == null || signatureHeader.isBlank()) {
            System.out.println("[SePay] ❌ Webhook không có header x-sepay-signature → Từ chối.");
            return false;
        }

        // Loại bỏ tiền tố "sha256=" nếu có
        String cleanSignature = signatureHeader.trim();
        if (cleanSignature.toLowerCase().startsWith("sha256=")) {
            cleanSignature = cleanSignature.substring(7).trim();
        }

        try {
            // SePay tạo signature từ: {timestamp}.{raw_body}
            String dataToSign;
            if (timestampHeader != null && !timestampHeader.isBlank()) {
                dataToSign = timestampHeader.trim() + "." + rawPayload;
            } else {
                dataToSign = rawPayload;
            }

            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
            );
            hmac.init(keySpec);
            byte[] hash = hmac.doFinal(dataToSign.getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            String calculatedSignature = hexString.toString();

            boolean isValid = calculatedSignature.equalsIgnoreCase(cleanSignature);
            if (!isValid) {
                // Thử fallback trực tiếp với rawPayload phòng khi SePay cấu hình không gửi timestamp
                byte[] fallbackHash = hmac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
                StringBuilder fallbackHex = new StringBuilder();
                for (byte b : fallbackHash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) fallbackHex.append('0');
                    fallbackHex.append(hex);
                }
                isValid = fallbackHex.toString().equalsIgnoreCase(cleanSignature);
            }

            if (!isValid) {
                System.out.println("[SePay] ❌ Chữ ký HMAC KHÔNG KHỚP!");
                System.out.println("  → Tính được : " + calculatedSignature);
                System.out.println("  → Nhận được : " + cleanSignature);
            } else {
                System.out.println("[SePay] ✅ Chữ ký HMAC hợp lệ!");
            }
            return isValid;
        } catch (Exception e) {
            System.err.println("[SePay] ❌ Lỗi khi xác thực HMAC: " + e.getMessage());
            return false;
        }
    }

    public boolean verifySignature(String rawPayload, String signatureHeader) {
        return verifySignature(rawPayload, signatureHeader, null);
    }
}
