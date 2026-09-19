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
}
