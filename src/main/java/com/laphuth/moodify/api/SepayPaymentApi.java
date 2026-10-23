package com.laphuth.moodify.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laphuth.moodify.dto.payment.SepayWebhookDto;
import com.laphuth.moodify.services.SepaySecurityService;
import com.laphuth.moodify.services.SubscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Controller xử lý luồng thanh toán SePay (VietQR chuyển khoản ngân hàng).
 *
 * Các endpoint:
 *   POST /api/subscriptions/checkout   → Tạo đơn thanh toán PENDING + sinh mã QR VietQR
 *   POST /hooks/sepay-payment          → Webhook nhận callback từ SePay khi tiền vào tài khoản
 *   GET  /api/subscriptions/payment-status/{orderCode} → Frontend polling trạng thái thanh toán
 */
@RestController
public class SepayPaymentApi {

    private final SubscriptionService subscriptionService;
    private final SepaySecurityService sepaySecurityService;
    private final ObjectMapper objectMapper;

    public SepayPaymentApi(
        SubscriptionService subscriptionService,
        SepaySecurityService sepaySecurityService
    ) {
        this.subscriptionService = subscriptionService;
        this.sepaySecurityService = sepaySecurityService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Tạo đơn thanh toán mới (PENDING) và trả về thông tin mã QR cho Frontend hiển thị.
     */
    @PostMapping("/api/subscriptions/checkout")
    public ResponseEntity<Map<String, Object>> checkout(
        Authentication authentication,
        @RequestBody Map<String, Object> request
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Object pkgIdObj = request.get("packageId");
        if (pkgIdObj == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "packageId là bắt buộc.");
        }
        Long packageId = Long.valueOf(pkgIdObj.toString());

        Map<String, Object> result = subscriptionService.createPendingPayment(authentication.getName(), packageId);
        return ResponseEntity.ok(result);
    }

    /**
     * Webhook endpoint nhận callback từ SePay khi có giao dịch tiền vào tài khoản ngân hàng.
     *
     * SePay gửi POST request với:
     *   - Header: x-sepay-signature (chữ ký HMAC-SHA256)
     *   - Body: JSON chứa thông tin giao dịch (số tiền, nội dung, mã tham chiếu...)
     *
     * Giao dịch được kiểm tra theo trạng thái đơn hàng trước khi kích hoạt gói.
     * Lưu ý: Endpoint này KHÔNG yêu cầu JWT token (đã được permitAll() trong SecurityConfig).
     */
    @PostMapping("/hooks/sepay-payment")
    public ResponseEntity<Map<String, Object>> handleSepayWebhook(
        @RequestHeader(value = "x-sepay-signature", required = false) String signature,
        @RequestHeader(value = "x-sepay-timestamp", required = false) String timestamp,
        @RequestBody String rawPayload
    ) {
        System.out.println("============================================");
        System.out.println("[SePay Webhook] Nhận callback từ SePay!");
        System.out.println("[SePay Webhook] Signature: " + signature);
        System.out.println("[SePay Webhook] Timestamp: " + timestamp);
        System.out.println("[SePay Webhook] Payload: " + rawPayload);
        System.out.println("============================================");

        // 1. Xác thực chữ ký HMAC-SHA256 theo chuẩn SePay ({timestamp}.{raw_body})
        if (!sepaySecurityService.verifySignature(rawPayload, signature, timestamp)) {
            System.out.println("[SePay Webhook] ❌ Chữ ký HMAC KHÔNG HỢP LỆ → Từ chối.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                    "success", false,
                    "message", "Chữ ký HMAC không hợp lệ."
                ));
        }

        // 2. Parse JSON sang DTO
        SepayWebhookDto webhookData;
        try {
            webhookData = objectMapper.readValue(rawPayload, SepayWebhookDto.class);
        } catch (Exception e) {
            System.err.println("[SePay Webhook] ❌ Lỗi parse JSON: " + e.getMessage());
            return ResponseEntity.badRequest()
                .body(Map.of("success", false, "message", "Dữ liệu JSON không hợp lệ."));
        }

        System.out.println("[SePay Webhook] ✅ HMAC hợp lệ. Giao dịch: " + webhookData);

        // 3. Xử lý nghiệp vụ và chỉ kích hoạt đơn vẫn đang chờ thanh toán.
        try {
            Map<String, Object> result = subscriptionService.processSepayWebhook(webhookData);
            System.out.println("[SePay Webhook] ✅ Kết quả: " + result);
            // SePay chỉ xác nhận webhook thành công với HTTP 200/201 và JSON
            // đúng dạng {"success":true}; không gửi kèm các field nghiệp vụ.
            if (Boolean.TRUE.equals(result.get("success"))) {
                return ResponseEntity.ok(Map.of("success", true));
            }

            // Từ chối nghiệp vụ (không tìm thấy đơn, sai số tiền...) vẫn là
            // callback đã xử lý được, nhưng không xác nhận thanh toán thành công.
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            System.err.println("[SePay Webhook] ❌ Lỗi xử lý: " + e.getMessage());
            // Lỗi hệ thống cần trả non-2xx để SePay có thể retry nếu được bật.
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Lỗi nội bộ khi xử lý giao dịch."));
        }
    }

    /**
     * API polling cho Frontend: kiểm tra trạng thái thanh toán của một đơn hàng.
     * Frontend sẽ gọi liên tục mỗi 3-5 giây cho đến khi trạng thái chuyển sang SUCCESS.
     *
     * @param orderCode Mã đơn hàng (ví dụ: MD1001)
     */
    @GetMapping("/api/subscriptions/payment-status/{orderCode}")
    public ResponseEntity<Map<String, Object>> getPaymentStatus(
        @PathVariable String orderCode
    ) {
        Map<String, Object> result = subscriptionService.getPaymentStatus(orderCode);
        return ResponseEntity.ok(result);
    }
}
