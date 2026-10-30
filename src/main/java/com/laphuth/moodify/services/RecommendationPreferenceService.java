package com.laphuth.moodify.services;

import com.laphuth.moodify.repositories.RecommendationStore;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import com.laphuth.moodify.entities.User;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Existing SQL tables are preference authorities; no fabricated per-track ALS observations. */
@Service
public class RecommendationPreferenceService {
    private final JdbcTemplate jdbc;
    private final PreferenceEntityResolver identities;
    private final int legacyOffset;
    public RecommendationPreferenceService(JdbcTemplate jdbc,PreferenceEntityResolver identities,
        @Value("${recommendations.legacy-offset-minutes:-420}") int legacyOffset) {
        this.jdbc=jdbc;this.identities=identities;this.legacyOffset=legacyOffset;
    }

    public PreferenceContext read(User user,RecommendationCatalog.Snapshot catalog,List<Signal> observed,Instant now) {
        Map<String,Integer> diagnostics=new LinkedHashMap<>();
        Set<String> follows=new TreeSet<>(),albums=new TreeSet<>(); Map<String,Double> albumPriors=new HashMap<>();
        List<String> artistRows=jdbc.query("SELECT artist_id FROM favorite_artists WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 1001",
            (rs,n)->rs.getString(1),user.getId());
        diagnostics.put("artist_source_rows",artistRows.size());
        for(String raw:artistRows.stream().limit(1000).distinct().toList()) {
            try { follows.add(identities.artist(raw).id()); }
            catch(ResponseStatusException invalid) { if(!unresolved(invalid))throw invalid;diagnostics.merge("unresolved_artists",1,Integer::sum); }
        }
        List<String> albumRows=jdbc.query("SELECT album_id FROM favorite_albums WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 1001",
            (rs,n)->rs.getString(1),user.getId());
        diagnostics.put("album_source_rows",albumRows.size());
        for(String raw:albumRows.stream().limit(1000).distinct().toList()) {
            try {
                var album=identities.album(user,raw);
                if(!albums.add(album.key())) continue;
                double budget=1.0/Math.max(1,album.trackIds().size());
                for(String track:album.trackIds()) if(catalog.tracks().containsKey(track)) albumPriors.merge(track,budget,Math::max);
            } catch(ResponseStatusException invalid) { if(!unresolved(invalid))throw invalid;diagnostics.merge("unresolved_albums",1,Integer::sum); }
        }
        if(artistRows.size()>1000||albumRows.size()>1000)diagnostics.put("preferences_truncated",1);
        Set<String> observedTracks=new HashSet<>(),observedArtists=new HashSet<>();
        for(Signal signal:observed) if(!signal.saved()&&signal.at().isAfter(now.minus(Duration.ofHours(2)))) {
            String id=catalog.canonical(signal.trackId());observedTracks.add(id);
            var track=catalog.resolve(id);if(track!=null&&track.getArtistSpotifyId()!=null)observedArtists.add(track.getArtistSpotifyId());
        }
        Map<String,Double> intentTracks=new HashMap<>(),intentArtists=new HashMap<>();
        jdbc.query("""
            SELECT selected_target_type,selected_target_id,TIMESTAMPADD(MINUTE,?,searched_at) selected_at
            FROM search_history WHERE user_id=? AND selected_target_id IS NOT NULL
              AND searched_at>=? ORDER BY searched_at DESC,id DESC LIMIT 50
            """,(org.springframework.jdbc.core.RowCallbackHandler) rs->{
                Instant at=RecommendationStore.instant(rs.getObject("selected_at"));
                if(at.isBefore(now.minus(Duration.ofHours(2)))||at.isAfter(now.plusSeconds(120)))return;
                double weight=Math.pow(.5,Math.max(0,Duration.between(at,now).toSeconds())/3600.0);
                String kind=rs.getString("selected_target_type"),raw=rs.getString("selected_target_id");
                if("TRACK".equals(kind)) {
                    var track=catalog.resolve(raw);
                    if(track!=null&&!observedTracks.contains(track.getId()))intentTracks.merge(track.getId(),weight,Math::max);
                } else if("ARTIST".equals(kind)) {
                    try { String id=identities.artist(raw).id();if(!observedArtists.contains(id))intentArtists.merge(id,weight*.6,Math::max); }
                    catch(ResponseStatusException invalid){if(!unresolved(invalid))throw invalid;diagnostics.merge("unresolved_search_artists",1,Integer::sum);}
                }
            },legacyOffset,user.getId(),RecommendationStore.utc(now.minus(Duration.ofHours(2)).minusSeconds(legacyOffset*60L)));
        diagnostics.put("resolved_followed_artists",follows.size());diagnostics.put("resolved_favorite_albums",albums.size());
        String fingerprint=fingerprint(follows,albums);
        return new PreferenceContext(Set.copyOf(follows),Map.copyOf(albumPriors),Map.copyOf(intentTracks),Map.copyOf(intentArtists),fingerprint,Map.copyOf(diagnostics));
    }

    @Transactional
    public void setFollowed(User user,String raw,boolean enabled) {
        try {
            var artist=identities.artist(raw);
            setState(user,"favorite_artists","artist_id",artist.id(),artist.aliases(),enabled);
        } catch(ResponseStatusException missing) {
            if(enabled||missing.getStatusCode().value()!=404)throw missing;
            setState(user,"favorite_artists","artist_id",raw,Set.of(raw),false);
        }
    }
    @Transactional
    public void setAlbumFavorite(User user,String raw,boolean enabled) {
        try {
            var album=identities.album(user,raw);
            setState(user,"favorite_albums","album_id",album.key(),album.aliases(),enabled);
        } catch(ResponseStatusException missing) {
            if(enabled||missing.getStatusCode().value()!=404)throw missing;
            setState(user,"favorite_albums","album_id",raw,Set.of(raw),false);
        }
    }
    @Transactional
    public boolean toggleAlbum(User user,String raw) {
        var album=identities.album(user,raw);
        lockUser(user);
        boolean next=!hasAlbumFavorite(user,album);
        setState(user,"favorite_albums","album_id",album.key(),album.aliases(),next);
        return next;
    }
    public boolean hasAlbumFavorite(User user,PreferenceEntityResolver.AlbumRef album) {
        return !matching(user,"favorite_albums","album_id",album.aliases()).isEmpty();
    }
    public Set<String> followedArtists(User user) {
        Set<String> result=new TreeSet<>();
        for(String raw:jdbc.query("SELECT artist_id FROM favorite_artists WHERE user_id=? ORDER BY id LIMIT 1000",(rs,n)->rs.getString(1),user.getId())) {
            try { result.add(identities.artist(raw).id()); } catch(ResponseStatusException invalid){if(!unresolved(invalid))throw invalid;}
        }
        return Set.copyOf(result);
    }
    public List<String> favoriteAlbumIds(User user) {
        return jdbc.query("SELECT album_id FROM favorite_albums WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 1000",(rs,n)->rs.getString(1),user.getId());
    }
    private List<Map<String,Object>> matching(User user,String table,String column,Set<String> aliases) {
        List<Object> args=new ArrayList<>();args.add(user.getId());args.addAll(aliases);
        String placeholders=String.join(",",Collections.nCopies(aliases.size(),"?"));
        return jdbc.queryForList("SELECT "+column+",created_at FROM "+table+" WHERE user_id=? AND "+column+" IN ("+placeholders+")",args.toArray());
    }
    private void lockUser(User user) {
        if(user.getStatus()!=com.laphuth.moodify.entities.enums.UserStatus.ACTIVE)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account is not active");
        try { jdbc.queryForObject("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE",Long.class,user.getId()); }
        catch(org.springframework.dao.EmptyResultDataAccessException inactive) { throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account is not active"); }
    }
    private void setState(User user,String table,String column,String canonical,Set<String> aliases,boolean enabled) {
        // Table/column are internal constants; user row serializes mutations without schema changes.
        lockUser(user);
        var rows=matching(user,table,column,aliases);
        if(enabled&&rows.size()==1&&canonical.equals(rows.getFirst().get(column)))return;
        List<Object> args=new ArrayList<>();args.add(user.getId());args.addAll(aliases);
        String placeholders=String.join(",",Collections.nCopies(aliases.size(),"?"));
        jdbc.update("DELETE FROM "+table+" WHERE user_id=? AND "+column+" IN ("+placeholders+")",args.toArray());
        if(enabled) jdbc.update("INSERT INTO "+table+" (user_id,"+column+",created_at) VALUES (?,?,?)",
            user.getId(),canonical,rows.isEmpty()?RecommendationStore.utc(Instant.now()):rows.getFirst().get("created_at"));
    }
    static String fingerprint(Collection<String> first,Collection<String> second) {
        String value=new TreeSet<>(first).toString()+"|"+new TreeSet<>(second);
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static boolean unresolved(ResponseStatusException e) {
        return e.getStatusCode().value()==404||e.getStatusCode().value()==409||e.getStatusCode().value()==400;
    }
}
