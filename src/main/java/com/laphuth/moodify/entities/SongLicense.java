package com.laphuth.moodify.entities;

import com.laphuth.moodify.entities.enums.LicenseStatus;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

@Entity
@Table(name = "song_licenses")
public class SongLicense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "track_id", nullable = false, length = 64)
    private String trackId;

    @Column(name = "distributor_id")
    private Long distributorId;

    @Column(name = "distribution_contract_id")
    private Long distributionContractId;

    @Column(name = "distribution_contract_code", length = 80)
    private String distributionContractCode;

    @Column(name = "license_type", nullable = false, length = 80)
    private String licenseType;

    @Column(name = "copyright_owner", length = 200)
    private String copyrightOwner;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LicenseStatus status = LicenseStatus.PENDING;

    @Column(name = "document_songlicenses_url", length = 1000)
    private String documentSonglicensesUrl;

    @Column(name = "acquisition_cost_vnd", precision = 18, scale = 0)
    private BigDecimal acquisitionCostVnd;

    @Column(name = "document_sha256", length = 64)
    private String documentSha256;

    @Column(name = "rights_evidence_status", nullable = false, length = 20)
    private String rightsEvidenceStatus = "UNVERIFIED";

    @Column(name = "rights_verified_by")
    private Long rightsVerifiedBy;

    @Column(name = "rights_verified_at")
    private LocalDateTime rightsVerifiedAt;

    @Column(name = "rights_review_note", length = 1000)
    private String rightsReviewNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTrackId() { return trackId; }
    public void setTrackId(String trackId) { this.trackId = trackId; }

    public Long getDistributorId() { return distributorId; }
    public void setDistributorId(Long distributorId) { this.distributorId = distributorId; }

    public Long getDistributionContractId() { return distributionContractId; }
    public void setDistributionContractId(Long distributionContractId) { this.distributionContractId = distributionContractId; }

    public String getDistributionContractCode() { return distributionContractCode; }
    public void setDistributionContractCode(String distributionContractCode) { this.distributionContractCode = distributionContractCode; }

    public String getLicenseType() { return licenseType; }
    public void setLicenseType(String licenseType) { this.licenseType = licenseType; }

    public String getCopyrightOwner() { return copyrightOwner; }
    public void setCopyrightOwner(String copyrightOwner) { this.copyrightOwner = copyrightOwner; }

    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate issueDate) { this.issueDate = issueDate; }

    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }

    public LicenseStatus getStatus() { return status; }
    public void setStatus(LicenseStatus status) { this.status = status; }

    public String getDocumentSonglicensesUrl() { return documentSonglicensesUrl; }
    public void setDocumentSonglicensesUrl(String documentSonglicensesUrl) { this.documentSonglicensesUrl = documentSonglicensesUrl; }
    public BigDecimal getAcquisitionCostVnd() { return acquisitionCostVnd; }
    public void setAcquisitionCostVnd(BigDecimal acquisitionCostVnd) { this.acquisitionCostVnd = acquisitionCostVnd; }

    public String getDocumentSha256() { return documentSha256; }
    public void setDocumentSha256(String v) { this.documentSha256 = v; }
    public String getRightsEvidenceStatus() { return rightsEvidenceStatus; }
    public void setRightsEvidenceStatus(String v) { this.rightsEvidenceStatus = v; }
    public Long getRightsVerifiedBy() { return rightsVerifiedBy; }
    public void setRightsVerifiedBy(Long v) { this.rightsVerifiedBy = v; }
    public LocalDateTime getRightsVerifiedAt() { return rightsVerifiedAt; }
    public void setRightsVerifiedAt(LocalDateTime v) { this.rightsVerifiedAt = v; }
    public String getRightsReviewNote() { return rightsReviewNote; }
    public void setRightsReviewNote(String v) { this.rightsReviewNote = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
