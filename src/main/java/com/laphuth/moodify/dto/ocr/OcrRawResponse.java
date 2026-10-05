package com.laphuth.moodify.dto.ocr;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class OcrRawResponse {
    private boolean success;

    @JsonProperty("document_uuid")
    private String documentUuid;

    @JsonProperty("processing_status")
    private String processingStatus;

    @JsonProperty("ocr_engine")
    private String ocrEngine;

    @JsonProperty("raw_text")
    private String rawText;

    @JsonProperty("extracted_fields")
    private Map<String, Object> extractedFields;

    private Double confidence;

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getDocumentUuid() {
        return documentUuid;
    }

    public void setDocumentUuid(String documentUuid) {
        this.documentUuid = documentUuid;
    }

    public String getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(String processingStatus) {
        this.processingStatus = processingStatus;
    }

    public String getOcrEngine() {
        return ocrEngine;
    }

    public void setOcrEngine(String ocrEngine) {
        this.ocrEngine = ocrEngine;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public Map<String, Object> getExtractedFields() {
        return extractedFields;
    }

    public void setExtractedFields(Map<String, Object> extractedFields) {
        this.extractedFields = extractedFields;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }
}
