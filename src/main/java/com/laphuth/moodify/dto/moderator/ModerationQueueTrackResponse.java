package com.laphuth.moodify.dto.moderator;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.repositories.SongLicenseDetailsProjection;

import java.util.List;

public record ModerationQueueTrackResponse(
    String id,
    String title,
    String artist,
    String genre,
    String duration,
    int durationSec,
    String coverUrl,
    String audioUrl,
    String submittedAt,
    String priority,
    String status,
    String releaseType,
    boolean explicitFlagByArtist,
    String lyricsPlain,
    List<Double> waveform,
    
    // License Info (from MySQL song_licenses)
    boolean licenseRecordExists,
    String licenseType,
    String copyrightOwner,
    Long distributorId,
    String distributorName,
    Long distributionContractId,
    String distributionContractCode,
    String issueDate,
    String expiryDate,
    String licenseStatus,
    String licenseDocumentUrl,

    // Audio Features (from MongoDB tracks.audio_features)
    Track.AudioFeatures audioFeatures
) {
    public static ModerationQueueTrackResponse from(Track track, SongLicenseDetailsProjection license) {
        String genre = (track.getGenres() != null && !track.getGenres().isEmpty()) 
            ? track.getGenres().get(0) 
            : "Pop";
        int durationSec = track.getDurationMs() != null ? track.getDurationMs() / 1000 : 210;
        String durationFormatted = (track.getDurationFormatted() != null && !track.getDurationFormatted().isBlank())
            ? track.getDurationFormatted()
            : "%d:%02d".formatted(durationSec / 60, durationSec % 60);

        return new ModerationQueueTrackResponse(
            track.getId(),
            track.getName(),
            track.getArtistName() != null ? track.getArtistName() : "Nghệ sĩ độc lập",
            genre,
            durationFormatted,
            durationSec,
            track.getImageUrl(),
            com.laphuth.moodify.services.AudioUrlResolver.resolve(track.getLocalPath()),
            track.getCreatedAt() != null ? track.getCreatedAt().toString() : "Vừa xong",
            "normal",
            track.getModerationStatus() != null ? track.getModerationStatus() : "pending",
            "Single",
            track.isExplicit(),
            track.getLyricsPlain(),
            List.of(0.2, 0.4, 0.7, 0.5, 0.9, 0.6, 0.8, 0.4, 0.3, 0.5),
            license != null,
            license != null ? license.getLicenseType() : null,
            license != null ? license.getCopyrightOwner() : null,
            license != null ? license.getDistributorId() : null,
            license != null ? license.getDistributorName() : null,
            license != null ? license.getDistributionContractId() : null,
            license != null ? license.getDistributionContractCode() : null,
            license != null ? license.getIssueDate() : null,
            license != null ? license.getExpiryDate() : null,
            license != null ? license.getLicenseStatus() : null,
            license != null ? license.getLicenseDocumentUrl() : null,
            track.getAudioFeatures()
        );
    }
}
