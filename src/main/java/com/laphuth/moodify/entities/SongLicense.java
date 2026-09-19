package com.laphuth.moodify.entities;

import com.laphuth.moodify.entities.enums.LicenseStatus;
import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

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

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
