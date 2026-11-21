package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.SongLicense;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import java.time.LocalDate;
import java.util.*;

/** Shared rules for serving, renewal, moderation and expiry. Null evidence fails closed. */
public final class CatalogLicensePolicy {
    private CatalogLicensePolicy() {}

    public static boolean simulated(Track track, SongLicense license) {
        return license != null && ("DEMO_SIMULATED".equalsIgnoreCase(license.getLicenseType())
            || "SIMULATED".equalsIgnoreCase(license.getRightsEvidenceStatus()))
            || track != null && track.getRecommendationDemo() != null
                && Boolean.TRUE.equals(track.getRecommendationDemo().get("simulated"));
    }

    public static boolean evidenceReady(Track track, SongLicense license, boolean demoEnabled) {
        if (license == null || license.getDocumentSonglicensesUrl() == null
            || license.getDocumentSonglicensesUrl().isBlank() || license.getDocumentSha256()==null
            || !license.getDocumentSha256().matches("[0-9a-f]{64}")) return false;
        if (simulated(track, license)) return demoEnabled
            && "DEMO_SIMULATED".equalsIgnoreCase(license.getLicenseType())
            && "SIMULATED".equalsIgnoreCase(license.getRightsEvidenceStatus());
        return "DIGITAL_STREAMING".equalsIgnoreCase(license.getLicenseType())
            && "VERIFIED".equalsIgnoreCase(license.getRightsEvidenceStatus())
            && license.getRightsVerifiedBy()!=null && license.getRightsVerifiedAt()!=null
            && license.getRightsReviewNote()!=null && !license.getRightsReviewNote().isBlank()
            && !"SIMULATION_DETECTED".equals(license.getDocumentInspectionStatus());
    }

    public static boolean effective(SongLicense license, LocalDate today) {
        return license != null && license.getStatus() == LicenseStatus.ACTIVE
            && license.getIssueDate() != null && !license.getIssueDate().isAfter(today)
            && (license.getExpiryDate() == null || !license.getExpiryDate().isBefore(today))
            && (license.getExpiryDate() == null || !license.getExpiryDate().isBefore(license.getIssueDate()));
    }

    public static boolean canServe(Track track, SongLicense license, LocalDate today, boolean demoEnabled) {
        return PublicTrackPolicy.isPublishedAudioTrack(track)
            && effective(license, today) && evidenceReady(track, license, demoEnabled);
    }

    /** A future/pending renewal does not invalidate a still effective agreement.
     * A latest explicit revocation does override older agreements. Canonical IDs
     * take precedence over legacy provider aliases; names never resolve identity.
     */
    public static SongLicense select(Track track, Collection<SongLicense> rows, LocalDate today) {
        if (track == null || track.getId() == null) return null;
        List<SongLicense> canonical = rows.stream().filter(l -> track.getId().equals(l.getTrackId())).toList();
        List<SongLicense> candidates = canonical.isEmpty()
            ? rows.stream().filter(l -> track.getSpotifyId() != null && track.getSpotifyId().equals(l.getTrackId())).toList()
            : canonical;
        Comparator<SongLicense> order = Comparator.comparing(l -> Objects.requireNonNullElse(l.getId(), 0L));
        SongLicense latest = candidates.stream().max(order).orElse(null);
        if (latest != null && latest.getStatus() == LicenseStatus.REVOKED) return latest;
        return candidates.stream().filter(l -> effective(l, today)).max(order).orElse(latest);
    }
}
