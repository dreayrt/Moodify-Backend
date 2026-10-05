package com.laphuth.moodify.dto.ocr;

import java.util.Map;

public class OcrExtractResponse {
    private boolean success;
    private String licenseType;
    private String copyrightOwner;
    private Long distributorId;
    private String distributorName;
    private String contractId;
    private String issueDate;
    private String expiryDate;
    private boolean perpetual;
    private Double confidence;
    private String message;
    private Map<String, Object> rawFields;

    public OcrExtractResponse() {}

    public OcrExtractResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getLicenseType() {
        return licenseType;
    }

    public void setLicenseType(String licenseType) {
        this.licenseType = licenseType;
    }

    public String getCopyrightOwner() {
        return copyrightOwner;
    }

    public void setCopyrightOwner(String copyrightOwner) {
        this.copyrightOwner = copyrightOwner;
    }

    public Long getDistributorId() {
        return distributorId;
    }

    public void setDistributorId(Long distributorId) {
        this.distributorId = distributorId;
    }

    public String getDistributorName() {
        return distributorName;
    }

    public void setDistributorName(String distributorName) {
        this.distributorName = distributorName;
    }

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public String getIssueDate() {
        return issueDate;
    }

    public void setIssueDate(String issueDate) {
        this.issueDate = issueDate;
    }

    public String getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }

    public boolean isPerpetual() {
        return perpetual;
    }

    public void setPerpetual(boolean perpetual) {
        this.perpetual = perpetual;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getRawFields() {
        return rawFields;
    }

    public void setRawFields(Map<String, Object> rawFields) {
        this.rawFields = rawFields;
    }
}
