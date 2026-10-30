package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.User;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Exact, namespaced preference identities. Never guesses a legacy ID from a name. */
@Component
public class PreferenceEntityResolver {
    private final MongoTemplate mongo;
    private final JdbcTemplate jdbc;

    public PreferenceEntityResolver(MongoTemplate mongo, JdbcTemplate jdbc) { this.mongo=mongo; this.jdbc=jdbc; }

    public record ArtistRef(String id, Set<String> aliases) {}
    public record AlbumRef(String key, Object displayId, Set<String> aliases, String title, String description,
        String imageUrl, LocalDate releaseDate, boolean isPublic, Long ownerId, String authorName,
        LocalDateTime createdAt, LocalDateTime updatedAt, List<String> trackIds) {}

    public ArtistRef artist(String raw) {
        validate(raw,64);
        var query=Query.query(new Criteria().orOperator(Criteria.where("_id").in(mongoIds(List.of(raw))),Criteria.where("spotify_id").is(raw)));
        query.fields().include("spotify_id");
        List<Document> matches=mongo.find(query.limit(3),Document.class,"artists");
        Set<String> canonical=new HashSet<>(), aliases=new HashSet<>();
        for (Document d:matches) {
            String id=Objects.toString(d.get("_id"),null), external=d.getString("spotify_id");
            String key=external==null||external.isBlank()?id:external;
            canonical.add(key); aliases.add(id); if(external!=null&&!external.isBlank()) aliases.add(external);
        }
        if(canonical.size()>1) throw conflict();
        if(canonical.isEmpty()) {
            if(!mongo.exists(Query.query(Criteria.where("artist_spotify_id").is(raw)),"tracks")) throw RecommendationCatalog.notFound();
            return new ArtistRef(raw,Set.of(raw));
        }
        return new ArtistRef(canonical.iterator().next(),Set.copyOf(aliases));
    }

    public AlbumRef album(User user,String raw) {
        validate(raw,80);
        boolean sqlOnly=raw.startsWith("sql:"), mongoOnly=raw.startsWith("mongo:");
        String id=sqlOnly?raw.substring(4):mongoOnly?raw.substring(6):raw;
        Map<String,Object> sqlAlbum=null;
        if(!mongoOnly && id.matches("[0-9]{1,18}")) {
            var rows=jdbc.queryForList("""
                SELECT a.id,a.user_id,a.title,a.description,a.cover_url,a.release_date,a.is_public,a.status,
                       a.created_at,a.updated_at,u.full_name author_name
                FROM albums a LEFT JOIN users u ON u.id=a.user_id WHERE a.id=?
                """,Long.parseLong(id));
            if(!rows.isEmpty()) sqlAlbum=rows.getFirst();
        }
        List<Document> mongoAlbums=sqlOnly?List.of():mongo.find(Query.query(new Criteria().orOperator(
            Criteria.where("_id").in(mongoIds(List.of(id))),Criteria.where("spotify_id").is(id))).limit(3),Document.class,"albums");
        if(sqlAlbum!=null&&!mongoAlbums.isEmpty() || mongoAlbums.size()>1) throw conflict();
        if(sqlAlbum!=null) {
            long owner=((Number)sqlAlbum.get("user_id")).longValue();
            boolean pub=truth(sqlAlbum.get("is_public"));
            if(!"ACTIVE".equals(sqlAlbum.get("status")) || !pub && owner!=user.getId()) throw RecommendationCatalog.notFound();
            List<String> refs=jdbc.query("SELECT track_id FROM album_tracks WHERE album_id=? ORDER BY track_order,id",
                (rs,n)->rs.getString(1),sqlAlbum.get("id"));
            String key="sql:"+sqlAlbum.get("id");
            return new AlbumRef(key,sqlAlbum.get("id"),Set.of(key,sqlAlbum.get("id").toString()),
                Objects.toString(sqlAlbum.get("title"),""),Objects.toString(sqlAlbum.get("description"),null),
                Objects.toString(sqlAlbum.get("cover_url"),null),date(sqlAlbum.get("release_date")),pub,owner,
                Objects.toString(sqlAlbum.get("author_name"),null),local(sqlAlbum.get("created_at")),local(sqlAlbum.get("updated_at")),List.copyOf(canonicalTracks(refs)));
        }
        if(mongoAlbums.isEmpty()) throw RecommendationCatalog.notFound();
        Document album=mongoAlbums.getFirst();
        Long owner=album.get("owner_user_id") instanceof Number number?number.longValue():null;
        boolean pub=!Boolean.FALSE.equals(album.get("is_public"))&&!"private".equalsIgnoreCase(album.getString("visibility"));
        String status=album.getString("status");
        if(!pub&&!Objects.equals(owner,user.getId()) || status!=null&&!Set.of("ACTIVE","PUBLISHED").contains(status.toUpperCase(Locale.ROOT))) throw RecommendationCatalog.notFound();
        String internal=Objects.toString(album.get("_id"));
        String key="mongo:"+internal;
        if(key.length()>80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Album identity exceeds preference storage limit");
        Set<String> alias=new HashSet<>(Set.of(internal,key));
        String external=album.getString("spotify_id");
        if(external!=null&&!external.isBlank()) alias.add(external);
        List<String> refs=album.get("track_ids") instanceof List<?> values ?
            new ArrayList<>(values.stream().filter(String.class::isInstance).map(String.class::cast).filter(v->!v.isBlank()).toList()):new ArrayList<>();
        Set<String> members=new LinkedHashSet<>(canonicalTracks(refs));
        Query related=Query.query(new Criteria().orOperator(Criteria.where("album_spotify_id").in(alias),Criteria.where("album_ids").in(alias)));
        related.fields().include("_id");
        for(Document d:mongo.find(related,Document.class,"tracks")) members.add(Objects.toString(d.get("_id")));
        return new AlbumRef(key,internal,Set.copyOf(alias),Objects.toString(album.get("name"),""),album.getString("description"),
            album.getString("image_url"),date(album.get("release_date")),pub,owner,album.getString("artist_name"),
            local(album.get("created_at")),local(album.get("updated_at")),List.copyOf(members));
    }

    public Set<String> canonicalTracks(Collection<String> refs) {
        if(refs.isEmpty()) return Set.of();
        Query query=Query.query(new Criteria().orOperator(Criteria.where("_id").in(mongoIds(refs)),Criteria.where("spotify_id").in(refs)));
        query.fields().include("spotify_id");
        List<Document> found=mongo.find(query,Document.class,"tracks");
        Map<String,String> aliases=trackAliases(found);
        Set<String> result=new LinkedHashSet<>();
        for(String raw:refs) if(aliases.containsKey(raw)) result.add(aliases.get(raw));
        return Collections.unmodifiableSet(result);
    }

    static Map<String,String> trackAliases(List<Document> found) {
        Map<String,String> map=new HashMap<>(); Set<String> canonical=new HashSet<>(),ambiguous=new HashSet<>();
        for(Document d:found) { String id=Objects.toString(d.get("_id"));canonical.add(id);map.put(id,id); }
        for(Document d:found) {
            String external=d.getString("spotify_id"),id=Objects.toString(d.get("_id"));
            if(external==null||external.isBlank()||canonical.contains(external)||ambiguous.contains(external)) continue;
            String old=map.putIfAbsent(external,id);
            if(old!=null&&!old.equals(id)) { map.remove(external);ambiguous.add(external); }
        }
        return map;
    }
    private static List<Object> mongoIds(Collection<String> refs) {
        List<Object> result=new ArrayList<>();
        for(String raw:refs) { if(raw==null)continue;result.add(raw);if(org.bson.types.ObjectId.isValid(raw))result.add(new org.bson.types.ObjectId(raw)); }
        return result;
    }
    private static void validate(String raw,int limit) {
        if(raw==null||raw.isBlank()||raw.length()>limit) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid preference identity");
    }
    private static ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Ambiguous identity; use an explicit album namespace or repair catalog aliases"); }
    private static boolean truth(Object v) { return Boolean.TRUE.equals(v)||v instanceof Number n&&n.intValue()!=0||v instanceof byte[] b&&b.length>0&&b[0]!=0; }
    private static LocalDate date(Object v) {
        try { if(v instanceof java.sql.Date d)return d.toLocalDate(); if(v instanceof LocalDate d)return d;
            return v==null?null:LocalDate.parse(v.toString().substring(0,10)); }
        catch(RuntimeException ignored) { return null; }
    }
    private static LocalDateTime local(Object v) {
        if(v==null)return null; if(v instanceof LocalDateTime d)return d;
        if(v instanceof java.sql.Timestamp d)return d.toLocalDateTime();
        if(v instanceof Date d)return LocalDateTime.ofInstant(d.toInstant(),ZoneOffset.UTC);
        return null;
    }
}
