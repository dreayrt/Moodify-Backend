package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.SongLicense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SongLicenseRepository extends JpaRepository<SongLicense, Long> {
    Optional<SongLicense> findByTrackId(String trackId);
    List<SongLicense> findByTrackIdIn(List<String> trackIds);
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
