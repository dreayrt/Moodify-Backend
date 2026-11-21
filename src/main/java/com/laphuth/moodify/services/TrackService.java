package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.track.TrackPageResponse;
import com.laphuth.moodify.dto.track.TrackResponse;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.SongLicense;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class TrackService {
    private static final int MAX_PAGE_SIZE = 500;

    private final TrackRepository trackRepository;
    private final SongLicenseRepository songLicenseRepository;

    private boolean demoCatalogEnabled;

    @org.springframework.beans.factory.annotation.Value("${recommendations.demo-catalog-enabled:false}")
    public void setDemoCatalogEnabled(boolean value) { demoCatalogEnabled = value; }

    public TrackService(TrackRepository trackRepository, SongLicenseRepository songLicenseRepository) {
        this.trackRepository = trackRepository;
        this.songLicenseRepository = songLicenseRepository;
    }

    /** Fresh authorization snapshot. No stale allow-list is used after revocation or SQL failure. */
    public Set<String> getIneligibleTrackIds() {
        return unavailable(trackRepository.findAll());
    }
    private Set<String> unavailable(List<Track> all) {
        Map<String, String> aliases = RecommendationCatalog.aliases(all);
        List<SongLicense> rows = aliases.isEmpty() ? List.of()
            : songLicenseRepository.findByTrackIdIn(new ArrayList<>(aliases.keySet()));
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        Set<String> blocked = new HashSet<>();
        for (Track track : all) {
            if (!CatalogLicensePolicy.canServe(track, CatalogLicensePolicy.select(track, rows.stream().filter(l->Objects.equals(aliases.get(l.getTrackId()),track.getId())).toList(), today), today, demoCatalogEnabled)) {
                blocked.add(track.getId());
                if (Objects.equals(aliases.get(track.getSpotifyId()),track.getId())) blocked.add(track.getSpotifyId());
            }
        }
        return blocked;
    }

    public void invalidateLicenseCache() { /* Authorization is read fresh. Kept for existing callers. */ }

    public boolean isTrackPubliclyAvailable(Track track, Set<String> ineligibleIds) {
        return PublicTrackPolicy.isPublishedAudioTrack(track) && ineligibleIds != null
            && !ineligibleIds.contains(track.getId());
    }

    public boolean isTrackPubliclyAvailable(Track track) {
        if (!PublicTrackPolicy.isPublishedAudioTrack(track)) return false;
        LocalDate today=LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        return CatalogLicensePolicy.canServe(track,effectiveLicenseFor(track),today,demoCatalogEnabled);
    }

    public SongLicense effectiveLicenseFor(Track track) {
        List<String> ids=new ArrayList<>(List.of(track.getId()));
        if(track.getSpotifyId()!=null&&!track.getSpotifyId().isBlank()) {
            String external=track.getSpotifyId();
            boolean internalCollision=trackRepository.findById(external).filter(t->!Objects.equals(t.getId(),track.getId())).isPresent();
            boolean ambiguous=trackRepository.findBySpotifyIdIn(List.of(external)).stream().anyMatch(t->!Objects.equals(t.getId(),track.getId()));
            if(!internalCollision&&!ambiguous)ids.add(external);
        }
        return CatalogLicensePolicy.select(track,songLicenseRepository.findByTrackIdIn(ids),LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    public java.time.LocalDateTime capOfflineExpiry(Track track,java.time.LocalDateTime subscriptionEnd) {
        SongLicense license=effectiveLicenseFor(track);
        if(license==null)throw new TrackNotFoundException(track.getId());
        java.time.LocalDateTime rightsEnd=license.getExpiryDate()==null?null:license.getExpiryDate().plusDays(1).atStartOfDay();
        return rightsEnd!=null&&rightsEnd.isBefore(subscriptionEnd)?rightsEnd:subscriptionEnd;
    }

    public static String removeAccents(String text) {
        if (text == null) return "";
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD);
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
        String result = pattern.matcher(normalized).replaceAll("");
        return result.replace('đ', 'd').replace('Đ', 'D').trim().toLowerCase();
    }

    private TrackPageResponse catalogPage(int page,int size,java.util.function.Predicate<Track> filter,boolean popularity) {
        int safePage=Math.max(0,page),safeSize=Math.clamp(size,1,MAX_PAGE_SIZE);
        List<Track> all=trackRepository.findAll();
        Set<String> blocked=unavailable(all);
        Comparator<Track> order=popularity
            ? Comparator.comparing(Track::getPopularity,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(t->Objects.toString(t.getId(),""))
            : Comparator.comparing(t->Objects.toString(t.getName(),""),String.CASE_INSENSITIVE_ORDER);
        List<Track> visible=all.stream().filter(t->isTrackPubliclyAvailable(t,blocked)).filter(filter).sorted(order).toList();
        int start=(int)Math.min((long)safePage*safeSize,visible.size()),end=Math.min(start+safeSize,visible.size());
        return new TrackPageResponse(visible.subList(start,end).stream().map(TrackResponse::fromSummary).toList(),safePage,safeSize,
            visible.size(),(int)Math.ceil((double)visible.size()/safeSize));
    }

    public TrackPageResponse getTracks(int page,int size,String query) {
        String normalized=removeAccents(Objects.toString(query,""));
        return catalogPage(page,size,t->normalized.isBlank() || removeAccents(Objects.toString(t.getName(),"")).contains(normalized)
            || removeAccents(Objects.toString(t.getArtistName(),"")).contains(normalized)
            || removeAccents(Objects.toString(t.getAlbumName(),"")).contains(normalized),false);
    }

    public Track getTrackEntity(String idOrSpotifyId) {
        return trackRepository.findById(idOrSpotifyId)
            .or(() -> trackRepository.findBySpotifyId(idOrSpotifyId))
            .orElseThrow(() -> new TrackNotFoundException(idOrSpotifyId));
    }

    public TrackResponse getTrack(String id) {
        Track track = getTrackEntity(id);
        if (!isTrackPubliclyAvailable(track)) {
            throw new TrackNotFoundException(id);
        }
        return TrackResponse.from(track);
    }

    public TrackPageResponse getTracksByGenre(String genre,int page,int size) {
        return getTracksByGenres(List.of(genre),page,size);
    }
    public TrackPageResponse getTracksByGenres(List<String> genres,int page,int size) {
        return catalogPage(page,size,t->t.getGenres()!=null && t.getGenres().stream().anyMatch(g->genres.stream().anyMatch(g::equalsIgnoreCase)),true);
    }
    public TrackPageResponse getTracksByArtist(String artist,int page,int size) {
        return catalogPage(page,size,t->Objects.equals(artist,t.getArtistSpotifyId()) || t.getArtistIds()!=null&&t.getArtistIds().contains(artist),true);
    }

    public static class TrackNotFoundException extends RuntimeException {
        public TrackNotFoundException(String id) {
            super("Track not found: " + id);
        }
    }
}
