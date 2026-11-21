package com.laphuth.moodify.dto.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;

/** Optional information transcribed from a contract. It does not verify signatures or rights. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgreementDetails(
        String licensorName, String licensorAddress, String licensorRepresentative, String licensorTitle,
        String licenseeName, String licenseeAddress, String licenseeRepresentative, String licenseeTitle,
        String signingPlace, String plannedSigningDate
) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public AgreementDetails {
        licensorName = clean(licensorName, 200);
        licensorAddress = clean(licensorAddress, 500);
        licensorRepresentative = clean(licensorRepresentative, 200);
        licensorTitle = clean(licensorTitle, 120);
        licenseeName = clean(licenseeName, 200);
        licenseeAddress = clean(licenseeAddress, 500);
        licenseeRepresentative = clean(licenseeRepresentative, 200);
        licenseeTitle = clean(licenseeTitle, 120);
        signingPlace = clean(signingPlace, 200);
        plannedSigningDate = clean(plannedSigningDate, 10);
        if (plannedSigningDate != null) {
            try {
                if (!plannedSigningDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    throw new IllegalArgumentException("Ngày dự kiến ký phải có dạng YYYY-MM-DD.");
                }
                LocalDate.parse(plannedSigningDate);
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("Ngày dự kiến ký không hợp lệ.", invalid);
            }
        }
    }

    public static AgreementDetails fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        if (json.length() > 8_000) throw new IllegalArgumentException("Thông tin hợp đồng quá dài.");
        try {
            AgreementDetails details = JSON.readValue(json, AgreementDetails.class);
            return details == null || details.isEmpty() ? null : details;
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Thông tin các bên hoặc lịch ký hợp đồng không hợp lệ.", invalid);
        }
    }

    @JsonIgnore
    public boolean isEmpty() {
        return Stream.of(licensorName, licensorAddress, licensorRepresentative, licensorTitle,
                licenseeName, licenseeAddress, licenseeRepresentative, licenseeTitle,
                signingPlace, plannedSigningDate).allMatch(value -> value == null);
    }

    private static String clean(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.strip();
        if (value.length() > maxLength || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Thông tin hợp đồng có trường quá dài hoặc chứa ký tự điều khiển.");
        }
        return value;
    }
}
