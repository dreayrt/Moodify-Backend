package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.ocr.OcrExtractResponse;
import com.laphuth.moodify.dto.ocr.OcrRawResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private final RestClient restClient;

    public OcrService(@Value("${ocr.service.base-url}") String baseUrl) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(15));
        requestFactory.setReadTimeout(Duration.ofSeconds(60));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    public OcrExtractResponse extractLicenseDocument(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new OcrExtractResponse(false, "Vui lòng chọn tập tin tài liệu PDF hoặc hình ảnh hợp lệ.");
        }

        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
                }
            };

            body.add("file", resource);
            body.add("target_fields", "license_type,copyright_owner,distributor,contract_id,issue_date,expiry_date");

            log.info("Gửi file '{}' ({}) tới OCR service", file.getOriginalFilename(), file.getSize());

            OcrRawResponse rawResponse = restClient.post()
                    .uri("/api/v1/ocr/pdf")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(OcrRawResponse.class);

            if (rawResponse == null || !rawResponse.isSuccess()) {
                return new OcrExtractResponse(false, "OCR service không xử lý được tài liệu.");
            }

            return mapToExtractResponse(rawResponse);
        } catch (IOException e) {
            log.error("Lỗi đọc dữ liệu tập tin tải lên: {}", e.getMessage(), e);
            return new OcrExtractResponse(false, "Lỗi đọc dữ liệu tập tin: " + e.getMessage());
        } catch (Exception e) {
            log.error("Lỗi khi kết nối tới OCR service: {}", e.getMessage(), e);
            return new OcrExtractResponse(false, "Không thể kết nối hoặc lỗi xử lý OCR service: " + e.getMessage());
        }
    }

    private OcrExtractResponse mapToExtractResponse(OcrRawResponse raw) {
        OcrExtractResponse response = new OcrExtractResponse();
        response.setSuccess(true);
        response.setConfidence(raw.getConfidence());
        response.setMessage("Bóc tách thông tin thành công");

        Map<String, Object> fields = raw.getExtractedFields();
        response.setRawFields(fields);

        if (fields == null || fields.isEmpty()) {
            return response;
        }

        // 1. License Type
        String rawLicenseType = getString(fields, "license_type");
        response.setLicenseType(normalizeLicenseType(rawLicenseType));

        // 2. Copyright Owner
        String rawOwner = getString(fields, "copyright_owner");
        if (rawOwner != null && !rawOwner.isBlank()) {
            response.setCopyrightOwner(rawOwner.trim());
        }

        // 3. Distributor
        String rawDistributor = getString(fields, "distributor");
        normalizeDistributor(rawDistributor, response);

        // 4. Contract ID
        String rawContractId = getString(fields, "contract_id");
        if (rawContractId != null && !rawContractId.isBlank()) {
            response.setContractId(rawContractId.trim());
        }

        // 5. Issue Date & Expiry Date
        String rawIssueDate = getString(fields, "issue_date");
        response.setIssueDate(parseFlexibleDate(rawIssueDate));

        String rawExpiryDate = getString(fields, "expiry_date");
        if (rawExpiryDate != null && (
                rawExpiryDate.toLowerCase(Locale.ROOT).contains("vô thời hạn") ||
                rawExpiryDate.toLowerCase(Locale.ROOT).contains("vo thoi han") ||
                rawExpiryDate.toLowerCase(Locale.ROOT).contains("perpetual") ||
                rawExpiryDate.toLowerCase(Locale.ROOT).contains("không thời hạn") ||
                rawExpiryDate.toLowerCase(Locale.ROOT).contains("khong thoi han")
        )) {
            response.setPerpetual(true);
            response.setExpiryDate(null);
        } else {
            String parsedExpiry = parseFlexibleDate(rawExpiryDate);
            if (parsedExpiry != null) {
                response.setExpiryDate(parsedExpiry);
                response.setPerpetual(false);
            }
        }

        return response;
    }

    private String normalizeLicenseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return "DIGITAL_STREAMING";
        }
        return raw.trim();
    }

    private void normalizeDistributor(String raw, OcrExtractResponse response) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String lower = raw.toLowerCase(Locale.ROOT).trim();

        if (lower.contains("moodify")) {
            response.setDistributorId(1L);
            response.setDistributorName("Moodify Direct Distribution (Nội bộ)");
            return;
        }
        if (lower.contains("distrokid")) {
            response.setDistributorId(2L);
            response.setDistributorName("DistroKid Music Group");
            return;
        }
        if (lower.contains("tunecore")) {
            response.setDistributorId(3L);
            response.setDistributorName("TuneCore Digital Media");
            return;
        }
        if (lower.contains("sony") || lower.contains("universal")) {
            response.setDistributorId(4L);
            response.setDistributorName("Universal / Sony Music Publishing");
            return;
        }

        try {
            long parsedId = Long.parseLong(lower);
            response.setDistributorId(parsedId);
        } catch (NumberFormatException ignored) {}
    }

    private String parseFlexibleDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String clean = raw.trim();

        // 1. Try standard YYYY-MM-DD
        try {
            LocalDate d = LocalDate.parse(clean);
            return d.format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {}

        // 2. Try DD/MM/YYYY or DD-MM-YYYY
        Pattern p = Pattern.compile("(\\d{1,2})[/\\-\\.](\\d{1,2})[/\\-\\.](\\d{4})");
        Matcher m = p.matcher(clean);
        if (m.find()) {
            int d = Integer.parseInt(m.group(1));
            int mon = Integer.parseInt(m.group(2));
            int y = Integer.parseInt(m.group(3));
            try {
                LocalDate date = LocalDate.of(y, mon, d);
                return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception ignored) {}
        }

        // 3. Try YYYY/MM/DD
        Pattern p2 = Pattern.compile("(\\d{4})[/\\-\\.](\\d{1,2})[/\\-\\.](\\d{1,2})");
        Matcher m2 = p2.matcher(clean);
        if (m2.find()) {
            int y = Integer.parseInt(m2.group(1));
            int mon = Integer.parseInt(m2.group(2));
            int d = Integer.parseInt(m2.group(3));
            try {
                LocalDate date = LocalDate.of(y, mon, d);
                return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (Exception ignored) {}
        }

        return null;
    }

    private String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
