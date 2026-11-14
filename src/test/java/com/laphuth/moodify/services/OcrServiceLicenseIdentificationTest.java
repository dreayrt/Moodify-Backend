package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.ocr.OcrExtractResponse;
import com.laphuth.moodify.dto.ocr.OcrRawResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OcrServiceLicenseIdentificationTest {
    private final OcrService service = new OcrService("http://ocr.invalid");

    private OcrExtractResponse map(String distributor) {
        OcrRawResponse raw = new OcrRawResponse();
        raw.setSuccess(true);
        raw.setExtractedFields(Map.of("distributor", distributor, "contract_id", "TEST-LNAB-20261110-0003"));
        return ReflectionTestUtils.invokeMethod(service, "mapToExtractResponse", raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Không qua trung gian", "Không qua trung gian; cấp quyền trực tiếp",
        "Khong qua don vi phan phoi", "Không sử dụng nhà phân phối", "Tự phát hành",
        "No intermediary", "Self-distributed", "0"})
    void directLicensingIsDetectedWithoutInventingDistributorId(String distributor) {
        OcrExtractResponse result = map(distributor);
        assertThat(result.isDirectLicensing()).isTrue();
        assertThat(result.getDistributorId()).isNull();
        assertThat(result.getDistributorName()).isEqualTo(distributor);
        assertThat(result.getContractId()).isEqualTo("TEST-LNAB-20261110-0003");
    }

    @Test
    void knownDistributorStillMapsToItsExistingOption() {
        OcrExtractResponse result = map("DistroKid Music Group");
        assertThat(result.isDirectLicensing()).isFalse();
        assertThat(result.getDistributorId()).isEqualTo(2L);
    }

    @Test
    void unknownDistributorKeepsItsNameForManualSelection() {
        OcrExtractResponse result = map("Independent Distribution Company");
        assertThat(result.isDirectLicensing()).isFalse();
        assertThat(result.getDistributorId()).isNull();
        assertThat(result.getDistributorName()).isEqualTo("Independent Distribution Company");
    }
}
