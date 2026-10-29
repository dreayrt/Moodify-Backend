package com.laphuth.moodify.recommendation;

import com.laphuth.moodify.entities.*;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import com.laphuth.moodify.repositories.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Canonical ID resolver and fail-closed eligibility for the recommendation surfaces. */
@Service
public class RecommendationCatalog {
    private final TrackRepository tracks;
    private final SongLicenseRepository licenses;
    private final PlaylistRepository playlists;
    private final UserRepository users;
    private final boolean requireLicense;

    public RecommendationCatalog(TrackRepository tracks, SongLicenseRepository licenses,
        PlaylistRepository playlists, UserRepository users,
        @Value("${recommendations.require-active-license:true}") boolean requireLicense) {
        this.tracks = tracks; this.licenses = licenses; this.playlists = playlists;
        this.users = users; this.requireLicense = requireLicense;
    }

    public User user(String principal) {
        if (principal == null || principal.isBlank() || "anonymousUser".equals(principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        User user=users.findByEmailOrUsername(principal,principal).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"User not found"));
        if(user.getStatus()!=com.laphuth.moodify.entities.enums.UserStatus.ACTIVE)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account is not active");
        return user;
    }

    public Snapshot snapshot() {
        List<Track> all = tracks.findAll();
        Map<String, SongLicense> byTrack = licensesByCanonicalId(all);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Map<String, Track> eligible = new LinkedHashMap<>();
        Map<String, String> aliases = aliases(all);
        for (Track track : all) {
            if (track.getId() == null) continue;
            if (eligible(track, byTrack.get(track.getId()), today, requireLicense)) eligible.put(track.getId(), track);
        }
        return new Snapshot(eligible, aliases);
    }

    /** Playback verification only needs this track and its direct license aliases, never all tracks. */
    public Track playableTrack(String rawId) {
        if(rawId==null||rawId.isBlank()) throw notFound();
        Track track=tracks.findById(rawId).or(()->tracks.findBySpotifyId(rawId)).orElseThrow(RecommendationCatalog::notFound);
        // Keep canonical-ID precedence even when another track's internal ID equals this Spotify alias.
        List<Track> identityScope=new ArrayList<>(List.of(track));
        String external=track.getSpotifyId();
        if(external!=null&&!external.isBlank()) {
            tracks.findById(external).ifPresent(identityScope::add);
            identityScope.addAll(tracks.findBySpotifyIdIn(List.of(external)));
        }
        SongLicense license=licensesByCanonicalId(identityScope).get(track.getId());
        if(!eligible(track,license,LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")),requireLicense)) throw notFound();
        return track;
    }

    private Map<String, SongLicense> licensesByCanonicalId(List<Track> all) {
        Map<String, String> aliases = aliases(all);
        Map<String, SongLicense> result = new HashMap<>();
        if (!aliases.isEmpty()) {
            for (SongLicense license : licenses.findByTrackIdIn(new ArrayList<>(aliases.keySet()))) {
                String canonical = aliases.get(license.getTrackId());
                SongLicense old = result.get(canonical);
                // An explicit canonical license takes precedence over its legacy external-ID alias.
                if (old == null || (!old.getTrackId().equals(canonical) && license.getTrackId().equals(canonical)) ||
                    (old.getTrackId().equals(license.getTrackId()) && Objects.requireNonNullElse(license.getId(),0L) > Objects.requireNonNullElse(old.getId(),0L))) {
                    result.put(canonical, license);
                }
            }
        }
        return result;
    }

    static Map<String, String> aliases(List<Track> all) {
        Map<String, String> aliases = new HashMap<>();
        Set<String> canonicalIds = all.stream().map(Track::getId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        canonicalIds.forEach(id -> aliases.put(id,id));
        Set<String> ambiguous = new HashSet<>();
        for (Track track : all) {
            if (track.getId() == null) continue;
            String external = track.getSpotifyId();
            if (external == null || external.isBlank() || canonicalIds.contains(external) || ambiguous.contains(external)) continue;
            String old = aliases.putIfAbsent(external,track.getId());
            if (old != null && !old.equals(track.getId())) { aliases.remove(external); ambiguous.add(external); }
        }
        return aliases;
    }

    public RecommendationTypes.Readiness readiness() {
        List<Track> all = tracks.findAll();
        var licenseMap = licensesByCanonicalId(all);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        Map<String, Integer> issues = new LinkedHashMap<>();
        for (String code : List.of("NOT_PUBLISHED","NOT_PUBLIC","NOT_APPROVED","NO_AUDIO","NO_LICENSE","LICENSE_NOT_EFFECTIVE")) issues.put(code,0);
        int eligible = 0;
        for (Track track : all) {
            SongLicense license = licenseMap.get(track.getId());
            if (eligible(track, license, today, requireLicense)) eligible++;
            if (!"published".equalsIgnoreCase(track.getStatus())) issues.merge("NOT_PUBLISHED",1,Integer::sum);
            if (!"public".equalsIgnoreCase(track.getVisibility())) issues.merge("NOT_PUBLIC",1,Integer::sum);
            if (!"approved".equalsIgnoreCase(track.getModerationStatus())) issues.merge("NOT_APPROVED",1,Integer::sum);
            if (track.getLocalPath() == null || track.getLocalPath().isBlank()) issues.merge("NO_AUDIO",1,Integer::sum);
            if (license == null) issues.merge("NO_LICENSE",1,Integer::sum);
            else if (license.getStatus() != LicenseStatus.ACTIVE ||
                (license.getIssueDate() != null && license.getIssueDate().isAfter(today)) ||
                (license.getExpiryDate() != null && license.getExpiryDate().isBefore(today))) issues.merge("LICENSE_NOT_EFFECTIVE",1,Integer::sum);
        }
        return new RecommendationTypes.Readiness(all.size(),eligible,publicPlaylists().size(),requireLicense,issues);
    }

    public Map<String,Object> review(int offset,int limit) {
        List<Track> all=tracks.findAll().stream().filter(t->t.getId()!=null)
            .sorted(Comparator.comparing(Track::getId)).toList();
        Map<String,SongLicense> mapped=licensesByCanonicalId(all);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        List<Map<String,Object>> pending=new ArrayList<>();
        int eligibleCount=0;
        for(Track track:all) {
            SongLicense license=mapped.get(track.getId());
            if(eligible(track,license,today,requireLicense)) { eligibleCount++;continue; }
            List<String> reasons=new ArrayList<>();
            if(!"published".equalsIgnoreCase(track.getStatus()))reasons.add("VERIFY_PUBLICATION");
            if(!"public".equalsIgnoreCase(track.getVisibility()))reasons.add("VERIFY_VISIBILITY");
            if(!"approved".equalsIgnoreCase(track.getModerationStatus()))reasons.add("VERIFY_MODERATION");
            if(track.getLocalPath()==null||track.getLocalPath().isBlank())reasons.add("VERIFY_AUDIO");
            if(license==null)reasons.add("VERIFY_LICENSE");
            else if(license.getStatus()!=LicenseStatus.ACTIVE ||
                (license.getIssueDate()!=null&&license.getIssueDate().isAfter(today)) ||
                (license.getExpiryDate()!=null&&license.getExpiryDate().isBefore(today)))reasons.add("VERIFY_LICENSE_EFFECTIVE_DATES_AND_STATUS");
            pending.add(Map.of("trackId",track.getId(),"name",Objects.toString(track.getName(),""),"reasons",reasons));
        }
        int from=Math.min(offset,pending.size()),to=(int)Math.min((long)from+limit,pending.size());
        return Map.of("generatedAt",Instant.now(),"readOnly",true,"totalTracks",all.size(),"eligibleTracks",eligibleCount,
            "pendingTracks",pending.size(),"offset",offset,"limit",limit,"items",pending.subList(from,to));
    }

    public static boolean eligible(Track track, SongLicense license, LocalDate date, boolean requireLicense) {
        if (track == null || track.getId() == null || !"published".equalsIgnoreCase(track.getStatus()) ||
            !"approved".equalsIgnoreCase(track.getModerationStatus()) ||
            !"public".equalsIgnoreCase(track.getVisibility()) ||
            track.getLocalPath() == null || track.getLocalPath().isBlank()) return false;
        if (license == null) return !requireLicense;
        return license.getStatus() == LicenseStatus.ACTIVE &&
            (license.getIssueDate() == null || !license.getIssueDate().isAfter(date)) &&
            (license.getExpiryDate() == null || !license.getExpiryDate().isBefore(date));
    }

    public List<Playlist> publicPlaylists() {
        return playlists.findByIsPublicTrueOrderByCreatedAtDesc().stream()
            .filter(p -> Boolean.TRUE.equals(p.getIsPublic())).toList();
    }

    public List<Playlist> ownPlaylists(User user) {
        Map<String,Playlist> own=new LinkedHashMap<>();
        for(Playlist p:playlists.findByUsernameOrderByCreatedAtDesc(user.getUsername()))if(owns(p,user))own.put(p.getId(),p);
        for(Playlist p:playlists.findByOwnerUserIdOrderByCreatedAtDesc(user.getId()))own.put(p.getId(),p);
        return List.copyOf(own.values());
    }

    public Playlist accessiblePlaylist(String id, User user) {
        Playlist playlist = playlists.findById(id).orElseThrow(RecommendationCatalog::notFound);
        if (!Boolean.TRUE.equals(playlist.getIsPublic()) && !owns(playlist, user)) throw notFound();
        return playlist;
    }

    public static boolean owns(Playlist playlist, User user) {
        if(user==null)return false;
        if(playlist.getOwnerUserId()!=null)return Objects.equals(playlist.getOwnerUserId(),user.getId());
        if(playlist.getUsername()==null||playlist.getUsername().isBlank())return false;
        return (Objects.equals(playlist.getUsername(), user.getUsername()) ||
            Objects.equals(playlist.getUsername(), user.getEmail()));
    }

    public static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Content not found or access denied");
    }

    public record Snapshot(Map<String, Track> tracks, Map<String, String> aliases) {
        public String canonical(String id) { return id == null ? null : aliases.getOrDefault(id, id); }
        public Track resolve(String id) { return tracks.get(canonical(id)); }
        public List<Track> playlistTracks(Playlist playlist) {
            return playlist.getTrackSpotifyIds().stream().map(this::resolve).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.collectingAndThen(
                    java.util.stream.Collectors.toMap(Track::getId, t -> t, (a,b) -> a, LinkedHashMap::new),
                    map -> List.copyOf(map.values())));
        }
    }
}
