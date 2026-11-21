package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Durable, idempotent cross-store operations. Tracks are hidden before SQL changes;
 * history and registry keys survive retirement. A failed operation remains retryable.
 */
@Service
public class CatalogIntegrityService {
    private static final Logger log = LoggerFactory.getLogger(CatalogIntegrityService.class);
    private final MongoTemplate mongo;
    private final JdbcTemplate sql;
    private final TransactionTemplate transaction;

    public CatalogIntegrityService(MongoTemplate mongo, JdbcTemplate sql, PlatformTransactionManager manager) {
        this.mongo=mongo; this.sql=sql;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private Document trackQuery(String id) {
        return new Document("_id", new Document("$in", ObjectId.isValid(id) ? List.of(id,new ObjectId(id)) : List.of(id)));
    }

    public String registerArtistIdentity(String identity) {
        Document match=new Document("$or",List.of(trackQuery(identity),new Document("spotify_id",identity)));
        List<Document> found=mongo.getCollection("artists").find(match).limit(2).into(new ArrayList<>());
        if(found.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Nghệ sĩ không tồn tại hoặc định danh không duy nhất.");
        var artist=mongo.getConverter().read(com.laphuth.moodify.entities.Artist.class,found.getFirst());
        registerArtist(artist);return artist.getId();
    }

    public void registerArtist(com.laphuth.moodify.entities.Artist artist) {
        if(artist==null||artist.getId()==null)throw new IllegalArgumentException("Existing artist identity required");
        String id=artist.getId();
        sql.update("INSERT IGNORE INTO catalog_artist_registry(artist_id) VALUES (?)",id);
        List<String> aliases=new ArrayList<>(List.of(id));
        if(artist.getSpotifyId()!=null&&!artist.getSpotifyId().startsWith("artist_"))aliases.add(artist.getSpotifyId());
        for(String alias:aliases) {
            sql.update("INSERT IGNORE INTO catalog_artist_aliases(alias_id,artist_id) VALUES (?,?)",alias,id);
            if(!id.equals(sql.queryForObject("SELECT artist_id FROM catalog_artist_aliases WHERE alias_id=?",String.class,alias)))
                throw new IllegalStateException("Ambiguous artist identity");
        }
    }

    public void registerTrack(Track track) {
        sql.update("INSERT INTO catalog_track_registry (track_id,state,updated_at) VALUES (?,'LIVE',UTC_TIMESTAMP()) ON DUPLICATE KEY UPDATE state='LIVE',updated_at=UTC_TIMESTAMP()",track.getId());
        registerAlias(track.getId(),track.getId());
        if(track.getSpotifyId()!=null&&!track.getSpotifyId().isBlank())registerAlias(track.getSpotifyId(),track.getId());
        List<String> artistIds=track.getArtistIds()==null?List.of():track.getArtistIds();
        if(artistIds.isEmpty())throw new IllegalStateException("Verified artist identity is required");
        for(String artistId:artistIds) {
            Document artist=mongo.getCollection("artists").find(trackQuery(artistId)).first();
            if(artist==null)throw new IllegalStateException("Artist does not exist: "+artistId);
            sql.update("INSERT IGNORE INTO catalog_artist_registry(artist_id) VALUES (?)",artistId);
            sql.update("INSERT IGNORE INTO catalog_artist_aliases(alias_id,artist_id) VALUES (?,?)",artistId,artistId);
            String provider=artist.getString("spotify_id");
            if(provider!=null)sql.update("INSERT IGNORE INTO catalog_artist_aliases(alias_id,artist_id) VALUES (?,?)",provider,artistId);
            sql.update("INSERT IGNORE INTO catalog_track_artists(track_id,artist_id) VALUES (?,?)",track.getId(),artistId);
        }

    }

    public Track updateMetadata(Track track) {
        Document fields=new Document("name",track.getName()).append("artist_name",track.getArtistName())
            .append("featured_artists",track.getFeaturedArtists()).append("album_name",track.getAlbumName())
            .append("description",track.getDescription()).append("genres",track.getGenres()).append("explicit",track.isExplicit())
            .append("lyrics_plain",track.getLyricsPlain()).append("status",track.getStatus()).append("visibility",track.getVisibility())
            .append("moderation_status",track.getModerationStatus()).append("updated_at",Date.from(Instant.now()));
        stagePatch(track.getId(),fields);
        return track;
    }

    public void stagePatch(String id,Document fields) {
        if(!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("Catalog patch requires SQL transaction");
        if(sql.update("UPDATE catalog_track_registry SET revision=revision+1 WHERE track_id=? AND state='LIVE'",id)!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Track was retired or is not registered");
        Long revision=sql.queryForObject("SELECT revision FROM catalog_track_registry WHERE track_id=?",Long.class,id);
        String operation=UUID.randomUUID().toString();
        fields.append("catalog_revision",revision);
        Document journal=new Document("_id",operation).append("revision",revision).append("kind","PATCH").append("track_id",id)
            .append("patch",fields).append("state","PENDING").append("created_at",Date.from(Instant.now()));
        mongo.getCollection("catalog_operations").insertOne(journal);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                try {
                    if(status==STATUS_COMMITTED)applyPatch(journal);
                    else mongo.getCollection("catalog_operations").updateOne(new Document("_id",operation),new Document("$set",new Document("state","ROLLED_BACK")));
                } catch(Exception failure) { log.error("Catalog patch remains pending: {}",operation,failure); }
            }
        });
        sql.update("INSERT INTO catalog_operation_commits(operation_id) VALUES (?)",operation);
    }

    private void applyPatch(Document journal) {
        String id=journal.getString("track_id");long revision=((Number)journal.get("revision")).longValue();
        Document earlier=mongo.getCollection("catalog_operations").find(new Document("kind","PATCH").append("state","PENDING")
            .append("track_id",id).append("revision",new Document("$lt",revision))).first();
        if(earlier!=null)throw new IllegalStateException("Earlier committed catalog patch must finish first");
        Document query=trackQuery(id).append("deletion_status",new Document("$exists",false))
            .append("$or",List.of(new Document("catalog_revision",new Document("$exists",false)),new Document("catalog_revision",new Document("$lt",revision))));
        if(mongo.getCollection("tracks").updateOne(query,new Document("$set",journal.get("patch",Document.class))).getMatchedCount()!=1) {
            Document current=mongo.getCollection("tracks").find(trackQuery(id)).first();
            if(current==null)throw new IllegalStateException("Track missing; catalog patch needs review");
            if(current.containsKey("deletion_status")) {
                mongo.getCollection("catalog_operations").updateOne(new Document("_id",journal.getString("_id")),new Document("$set",new Document("state","SUPERSEDED")));return;
            }
            if(!current.containsKey("catalog_revision")||((Number)current.get("catalog_revision")).longValue()<revision)
                throw new IllegalStateException("Catalog revision conflict");
        }
        finish(journal.getString("_id"));
    }

    public void abortUnstagedUpload(String id) {
        if(id!=null)mongo.getCollection("tracks").deleteOne(trackQuery(id).append("moderation_status","pending").append("status","draft"));
    }

    private void registerAlias(String alias,String id) {
        sql.update("INSERT IGNORE INTO catalog_track_aliases (alias_id,track_id) VALUES (?,?)",alias,id);
        String actual=sql.queryForObject("SELECT track_id FROM catalog_track_aliases WHERE alias_id=?",String.class,alias);
        if(!id.equals(actual))throw new IllegalStateException("Ambiguous catalog identity: "+alias);
    }

    /** Must be called before saving the SQL license in the caller's transaction. */
    public void stageUpload(Track track,List<Path> generatedFiles,Runnable afterCommit) {
        String operation=UUID.randomUUID().toString();
        Document journal=new Document("_id",operation).append("kind","UPLOAD").append("track_id",track.getId())
            .append("state","PENDING").append("created_at",Date.from(Instant.now()))
            .append("files",generatedFiles.stream().map(p->p.toAbsolutePath().normalize().toString()).toList());
        mongo.getCollection("catalog_operations").insertOne(journal);
        mongo.getCollection("tracks").updateOne(trackQuery(track.getId()),new Document("$set",new Document("ingest_state","PENDING_SQL").append("ingest_operation_id",operation)));
        if(!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Upload requires an active SQL transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status==STATUS_COMMITTED) {
                    try {
                        mongo.getCollection("tracks").updateOne(trackQuery(track.getId()),new Document("$set",new Document("ingest_state","READY")));
                        finish(operation);
                        if(afterCommit!=null)afterCommit.run();
                    } catch(Exception failure) { log.error("Upload finalization pending for {}",operation,failure); }
                } else compensateUpload(journal);
            }
        });
        sql.update("INSERT INTO catalog_operation_commits(operation_id) VALUES (?)",operation);
        registerTrack(track);
    }

    private void finish(String operation) {
        mongo.getCollection("catalog_operations").updateOne(new Document("_id",operation),new Document("$set",new Document("state","COMPLETE").append("completed_at",Date.from(Instant.now()))));
    }

    private void compensateUpload(Document journal) {
        String id=journal.getString("track_id");
        try {
            Document query=trackQuery(id).append("ingest_operation_id",journal.getString("_id")).append("moderation_status","pending");
            mongo.getCollection("tracks").deleteOne(query);
            if(mongo.getCollection("tracks").countDocuments(trackQuery(id))!=0)throw new IllegalStateException("Upload changed; compensation needs review");
            for(String value:journal.getList("files",String.class,List.of())) {
                Path path=Path.of(value).toAbsolutePath().normalize();
                Path root=Path.of("uploads").toAbsolutePath().normalize();
                if(!path.startsWith(root))throw new IllegalStateException("Generated path outside uploads");
                Files.deleteIfExists(path);
            }
            mongo.getCollection("catalog_operations").updateOne(new Document("_id",journal.getString("_id")),new Document("$set",new Document("state","ROLLED_BACK").append("completed_at",Date.from(Instant.now()))));
        } catch(Exception failure) { log.error("Upload compensation pending for {}",journal.getString("_id"),failure); }
    }

    public void retire(String id) {
        var tracks=mongo.getCollection("tracks");
        Document before=tracks.find(trackQuery(id)).first();
        if(before==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Track not found");
        String operation="retire:"+id;
        List<String> aliases=new ArrayList<>(List.of(id));
        String external=before.getString("spotify_id");
        if(external!=null&&!external.isBlank()&&!external.equals(id))aliases.add(external);
        mongo.getCollection("catalog_operations").updateOne(new Document("_id",operation),new Document("$setOnInsert",new Document("kind","RETIRE").append("track_id",id)
            .append("state","PENDING").append("aliases",aliases).append("before",before).append("created_at",Date.from(Instant.now()))),new UpdateOptions().upsert(true));
        tracks.updateOne(trackQuery(id),new Document("$set",new Document("status","archived").append("visibility","private")
            .append("deletion_status","PENDING").append("deleted_at",Date.from(Instant.now()))));
        completeRetirement(operation,id,aliases);
    }

    private void completeRetirement(String operation,String id,List<String> aliases) {
        String placeholders=String.join(",",Collections.nCopies(aliases.size(),"?"));
        transaction.executeWithoutResult(status->{
            sql.update("UPDATE song_licenses SET status='REVOKED' WHERE track_id IN ("+placeholders+")",aliases.toArray());
            sql.update("UPDATE offline_downloads SET status='EXPIRED',updated_at=UTC_TIMESTAMP() WHERE track_id IN ("+placeholders+")",aliases.toArray());
            sql.update("UPDATE catalog_track_registry SET state='RETIRED',updated_at=UTC_TIMESTAMP() WHERE track_id=?",id);
            sql.update("UPDATE content_review_requests SET status='CANCELLED',resolved_at=UTC_TIMESTAMP() WHERE content_type='TRACK' AND content_id IN ("+placeholders+") AND status IN ('PENDING','IN_REVIEW')",aliases.toArray());
            sql.update("DELETE FROM karaoke_room_queue WHERE track_id IN ("+placeholders+")",aliases.toArray());
            sql.update("UPDATE karaoke_rooms SET current_track_id=NULL,playback_status='IDLE',playback_position_ms=0,playback_version=playback_version+1 WHERE current_track_id IN ("+placeholders+")",aliases.toArray());
            sql.update("INSERT IGNORE INTO catalog_operation_commits(operation_id) VALUES (?)",operation);
        });
        mongo.getCollection("playlists").updateMany(new Document("track_spotify_ids",new Document("$in",aliases)),new Document("$pull",new Document("track_spotify_ids",new Document("$in",aliases))).append("$inc",new Document("revision",1)).append("$set",new Document("updated_at",Date.from(Instant.now()))));
        mongo.getCollection("albums").updateMany(new Document("track_ids",new Document("$in",aliases)),new Document("$pull",new Document("track_ids",new Document("$in",aliases))).append("$set",new Document("updated_at",Date.from(Instant.now()))));
        mongo.getCollection("tracks").updateOne(trackQuery(id),new Document("$set",new Document("deletion_status","COMPLETE")));
        finish(operation);
    }

    @Scheduled(fixedDelayString="${catalog.integrity.retry-ms:60000}")
    public void retryPending() {
        for(Document operation:mongo.getCollection("catalog_operations").find(new Document("state","PENDING")).limit(50)) {
            try {
                if("RETIRE".equals(operation.getString("kind")))completeRetirement(operation.getString("_id"),operation.getString("track_id"),operation.getList("aliases",String.class));
                else if(operation.getDate("created_at").toInstant().isBefore(Instant.now().minusSeconds(300))) {
                    Long count=sql.queryForObject("SELECT COUNT(*) FROM catalog_operation_commits WHERE operation_id=?",Long.class,operation.getString("_id"));
                    if(count!=null&&count>0) {
                        if("PATCH".equals(operation.getString("kind")))applyPatch(operation);
                        else {
                            mongo.getCollection("tracks").updateOne(trackQuery(operation.getString("track_id")),new Document("$set",new Document("ingest_state","READY")));
                            finish(operation.getString("_id"));
                        }
                    } else if("UPLOAD".equals(operation.getString("kind")))compensateUpload(operation);
                    else mongo.getCollection("catalog_operations").updateOne(new Document("_id",operation.getString("_id")),new Document("$set",new Document("state","ROLLED_BACK")));
                }
            } catch(Exception failure) { log.error("Catalog operation remains pending: {}",operation.getString("_id"),failure); }
        }
    }
}
