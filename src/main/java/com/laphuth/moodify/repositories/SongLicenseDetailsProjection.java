package com.laphuth.moodify.repositories;

/** Read model joining the license to its distributor and distribution contract. */
public interface SongLicenseDetailsProjection {
    String getTrackId();
    String getLicenseType();
    String getCopyrightOwner();
    Long getDistributorId();
    String getDistributorName();
    Long getDistributionContractId();
    String getDistributionContractCode();
    String getIssueDate();
    String getExpiryDate();
    String getLicenseStatus();
    String getLicenseDocumentUrl();
}
