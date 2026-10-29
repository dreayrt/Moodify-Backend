package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.SongLicense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SongLicenseRepository extends JpaRepository<SongLicense, Long> {
    Optional<SongLicense> findByTrackId(String trackId);
    List<SongLicense> findByTrackIdIn(List<String> trackIds);

    @Query(value = """
        SELECT sl.track_id AS trackId,
               sl.license_type AS licenseType,
               sl.copyright_owner AS copyrightOwner,
               sl.distributor_id AS distributorId,
               d.company_name AS distributorName,
               sl.distribution_contract_id AS distributionContractId,
               COALESCE(sl.distribution_contract_code, dc.contract_code) AS distributionContractCode,
               COALESCE(DATE_FORMAT(sl.issue_date, '%Y-%m-%d'),
                        DATE_FORMAT(dc.effective_from, '%Y-%m-%d')) AS issueDate,
               COALESCE(DATE_FORMAT(sl.expiry_date, '%Y-%m-%d'),
                        DATE_FORMAT(dc.effective_to, '%Y-%m-%d')) AS expiryDate,
               sl.status AS licenseStatus,
               sl.document_songlicenses_url AS licenseDocumentUrl
        FROM song_licenses sl
        LEFT JOIN distributors d ON d.id = sl.distributor_id
        LEFT JOIN distribution_contracts dc ON dc.id = sl.distribution_contract_id
        WHERE sl.track_id IN (:trackIds)
        ORDER BY sl.updated_at DESC, sl.id DESC
        """, nativeQuery = true)
    List<SongLicenseDetailsProjection> findDetailsByTrackIdIn(@Param("trackIds") List<String> trackIds);

    void deleteByTrackId(String trackId);

    @org.springframework.data.jpa.repository.Query("SELECT sl FROM SongLicense sl WHERE sl.status = com.laphuth.moodify.entities.enums.LicenseStatus.ACTIVE AND sl.expiryDate IS NOT NULL AND sl.expiryDate < :today")
    List<SongLicense> findExpiredActiveLicenses(@org.springframework.data.repository.query.Param("today") java.time.LocalDate today);

    @org.springframework.data.jpa.repository.Query("SELECT sl FROM SongLicense sl WHERE sl.status = com.laphuth.moodify.entities.enums.LicenseStatus.EXPIRED OR (sl.expiryDate IS NOT NULL AND sl.expiryDate < :today)")
    List<SongLicense> findAllExpiredLicenses(@org.springframework.data.repository.query.Param("today") java.time.LocalDate today);

    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT sl.trackId FROM SongLicense sl WHERE sl.trackId IS NOT NULL AND sl.status IN (com.laphuth.moodify.entities.enums.LicenseStatus.EXPIRED, com.laphuth.moodify.entities.enums.LicenseStatus.REVOKED)")
    List<String> findIneligibleTrackIds();

    @org.springframework.data.jpa.repository.Query("SELECT sl FROM SongLicense sl WHERE sl.status = com.laphuth.moodify.entities.enums.LicenseStatus.EXPIRED")
    List<SongLicense> findExpiredLicenses();

    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT sl.trackId FROM SongLicense sl WHERE sl.trackId IS NOT NULL AND (sl.status = com.laphuth.moodify.entities.enums.LicenseStatus.EXPIRED OR sl.status = com.laphuth.moodify.entities.enums.LicenseStatus.REVOKED OR (sl.expiryDate IS NOT NULL AND sl.expiryDate < :today))")
    List<String> findExpiredOrIneligibleTrackIds(@org.springframework.data.repository.query.Param("today") java.time.LocalDate today);

    @org.springframework.data.jpa.repository.Query("SELECT sl FROM SongLicense sl WHERE sl.status = com.laphuth.moodify.entities.enums.LicenseStatus.ACTIVE AND sl.issueDate IS NOT NULL AND sl.issueDate > :today")
    List<SongLicense> findFutureLicenses(@org.springframework.data.repository.query.Param("today") java.time.LocalDate today);
}
