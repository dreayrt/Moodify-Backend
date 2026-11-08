package com.laphuth.moodify.services;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.Date;

/** Resolves a published historical version without ever exposing an unreviewed draft. */
public final class KaraokeAssetVersions {
    private KaraokeAssetVersions() {}
    public static Document resolve(MongoTemplate mongo, String songId, String version) {
        Document active = mongo.findOne(Query.query(Criteria.where("songId").is(songId).and("status").is("ready")), Document.class, "karaoke_assets");
        if (version == null || (active != null && version.equals(active.getString("assetVersion")))) {
            if (active != null) return active;
        } else {
            Document old = mongo.findOne(Query.query(Criteria.where("songId").is(songId).and("assetVersion").is(version)
                .orOperator(Criteria.where("publishedAt").exists(true), Criteria.where("status").is("ready"))), Document.class, "karaoke_asset_versions");
            if (old != null) return old;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Karaoke asset version is not available");
    }
    public static Document forRoom(MongoTemplate mongo, Long roomId, String runId, String songId) {
        if (runId == null || runId.isBlank()) return resolve(mongo, songId, null);
        String key = roomId + ":" + runId;
        Document pin = mongo.findById(key, Document.class, "karaoke_room_asset_pins");
        if (pin == null) {
            Document active = resolve(mongo, songId, null);
            try {
                pin = mongo.findAndModify(Query.query(Criteria.where("_id").is(key)),
                    new Update().setOnInsert("songId", songId).setOnInsert("assetVersion", active.getString("assetVersion")).setOnInsert("createdAt", new Date()),
                    FindAndModifyOptions.options().upsert(true).returnNew(true), Document.class, "karaoke_room_asset_pins");
            } catch (org.springframework.dao.DuplicateKeyException simultaneousJoin) {
                pin = mongo.findById(key, Document.class, "karaoke_room_asset_pins");
                if (pin == null) throw simultaneousJoin;
            }
        }
        return resolve(mongo, songId, pin.getString("assetVersion"));
    }
}
