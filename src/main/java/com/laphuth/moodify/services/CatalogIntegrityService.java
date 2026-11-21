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

/** Coordinates catalog writes that span MySQL, MongoDB and uploaded files.
 * MongoDB track changes are applied only after the SQL transaction commits;
 * the catalog_operations journal keeps failed steps retryable.
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

    /** Resolves an artist by MongoDB id or provider id; the match must be unique. */
    public String registerArtistIdentity(String identity) {
        Document match=new Document("$or",List.of(trackQuery(identity),new Document("spotify_id",identity)));
        List<Document> found=mongo.getCollection("artists").find(match).limit(2).into(new ArrayList<>());
        if(found.size()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Nghệ sĩ không tồn tại hoặc định danh không duy nhất.");
        return mongo.getConverter().read(com.laphuth.moodify.entities.Artist.class,found.getFirst()).getId();
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

    /** Applies the patch to MongoDB once the caller's SQL transaction commits. */
    public void stagePatch(String id,Document fields) {
        if(!TransactionSynchronizationManager.isSynchronizationActive())throw new IllegalStateException("Catalog patch requires SQL transaction");
        Document current=mongo.getCollection("tracks").find(trackQuery(id)).first();
        if(current==null||current.containsKey("deletion_status"))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Track was retired or does not exist");
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED)return;
                try { applyPatch(id,fields); }
                catch(Exception failure) {
                    String operation=UUID.randomUUID().toString();
                    log.error("Catalog patch for {} queued for retry as {}",id,operation,failure);
                    mongo.getCollection("catalog_operations").insertOne(new Document("_id",operation).append("kind","PATCH")
                        .append("track_id",id).append("patch",fields).append("committed",true).append("state","PENDING")
                        .append("created_at",Date.from(Instant.now())));
                }
            }
        });
    }

    private void applyPatch(String id,Document fields) {
        Document query=trackQuery(id).append("deletion_status",new Document("$exists",false));
        Document update=new Document("$set",fields).append("$inc",new Document("catalog_revision",1));
        if(mongo.getCollection("tracks").updateOne(query,update).getMatchedCount()!=1
                && mongo.getCollection("tracks").countDocuments(trackQuery(id))==0)
            throw new IllegalStateException("Track missing; catalog patch needs review");
    }

    public void abortUnstagedUpload(String id) {
        if(id!=null)mongo.getCollection("tracks").deleteOne(trackQuery(id).append("moderation_status","pending").append("status","draft"));
    }

    private void requireExistingArtists(Track track) {
        List<String> artistIds=track.getArtistIds()==null?List.of():track.getArtistIds();
        if(artistIds.isEmpty())throw new IllegalStateException("Verified artist identity is required");
        for(String artistId:artistIds)
            if(mongo.getCollection("artists").countDocuments(trackQuery(artistId))==0)
                throw new IllegalStateException("Artist does not exist: "+artistId);
    }

    /** Must be called before saving the SQL license in the caller's transaction. */
    public void stageUpload(Track track,List<Path> generatedFiles,Runnable afterCommit) {
        requireExistingArtists(track);
        if(!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Upload requires an active SQL transaction");
        }
        String operation=UUID.randomUUID().toString();
        Document journal=new Document("_id",operation).append("kind","UPLOAD").append("track_id",track.getId())
            .append("state","PENDING").append("created_at",Date.from(Instant.now()))
            .append("files",generatedFiles.stream().map(p->p.toAbsolutePath().normalize().toString()).toList());
        mongo.getCollection("catalog_operations").insertOne(journal);
        mongo.getCollection("tracks").updateOne(trackQuery(track.getId()),new Document("$set",new Document("ingest_state","PENDING_SQL").append("ingest_operation_id",operation)));
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
            sql.update("UPDATE content_review_requests SET status='CANCELLED',resolved_at=UTC_TIMESTAMP() WHERE content_type='TRACK' AND content_id IN ("+placeholders+") AND status IN ('PENDING','IN_REVIEW')",aliases.toArray());
            sql.update("DELETE FROM karaoke_room_queue WHERE track_id IN ("+placeholders+")",aliases.toArray());
            sql.update("UPDATE karaoke_rooms SET current_track_id=NULL,playback_status='IDLE',playback_position_ms=0,playback_version=playback_version+1 WHERE current_track_id IN ("+placeholders+")",aliases.toArray());
        });
        mongo.getCollection("playlists").updateMany(new Document("track_spotify_ids",new Document("$in",aliases)),new Document("$pull",new Document("track_spotify_ids",new Document("$in",aliases))).append("$inc",new Document("revision",1)).append("$set",new Document("updated_at",Date.from(Instant.now()))));
        mongo.getCollection("albums").updateMany(new Document("track_ids",new Document("$in",aliases)),new Document("$pull",new Document("track_ids",new Document("$in",aliases))).append("$set",new Document("updated_at",Date.from(Instant.now()))));
        mongo.getCollection("tracks").updateOne(trackQuery(id),new Document("$set",new Document("deletion_status","COMPLETE")));
        finish(operation);
    }

    private void markState(Document operation,String state) {
        mongo.getCollection("catalog_operations").updateOne(new Document("_id",operation.getString("_id")),
            new Document("$set",new Document("state",state).append("completed_at",Date.from(Instant.now()))));
    }

    @Scheduled(fixedDelayString="${catalog.integrity.retry-ms:60000}")
    public void retryPending() {
        for(Document operation:mongo.getCollection("catalog_operations").find(new Document("state","PENDING")).limit(50)) {
            try {
                String kind=operation.getString("kind");
                if("RETIRE".equals(kind))completeRetirement(operation.getString("_id"),operation.getString("track_id"),operation.getList("aliases",String.class));
                else if("PATCH".equals(kind)) {
                    // Only patches recorded after a confirmed SQL commit are replayed; older journal entries are obsolete.
                    if(Boolean.TRUE.equals(operation.getBoolean("committed"))) {
                        applyPatch(operation.getString("track_id"),operation.get("patch",Document.class));
                        finish(operation.getString("_id"));
                    } else markState(operation,"SUPERSEDED");
                }
                else if(operation.getDate("created_at").toInstant().isBefore(Instant.now().minusSeconds(300))) {
                    // The upload's license row is written in the same SQL transaction, so it marks the commit.
                    Long count=sql.queryForObject("SELECT COUNT(*) FROM song_licenses WHERE track_id=?",Long.class,operation.getString("track_id"));
                    if(count!=null&&count>0) {
                        mongo.getCollection("tracks").updateOne(trackQuery(operation.getString("track_id")),new Document("$set",new Document("ingest_state","READY")));
                        finish(operation.getString("_id"));
                    } else if("UPLOAD".equals(kind))compensateUpload(operation);
                    else markState(operation,"ROLLED_BACK");
                }
            } catch(Exception failure) { log.error("Catalog operation remains pending: {}",operation.getString("_id"),failure); }
        }
    }
}
