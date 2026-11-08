package com.laphuth.moodify.repositories;

import java.time.Duration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/** Two bounded Mongo collections; no recommendation DDL or writes in MySQL. */
@Component
public class RecommendationPersistence {
    public static final String STATE = "recommendation_state";
    public static final String EVENTS = "recommendation_events";
    private final MongoTemplate mongo;
    private volatile boolean ready;

    public RecommendationPersistence(MongoTemplate mongo) { this.mongo = mongo; }

    public synchronized void requireReady() {
        if (ready) return;
        mongo.indexOps(STATE).ensureIndex(new Index().named("rec_state_user")
            .on("kind", Sort.Direction.ASC).on("user_id", Sort.Direction.ASC));
        mongo.indexOps(STATE).ensureIndex(new Index().named("rec_state_campaign")
            .on("kind", Sort.Direction.ASC).on("status", Sort.Direction.ASC).on("starts_at_utc", Sort.Direction.ASC));
        mongo.indexOps(STATE).ensureIndex(new Index().named("rec_state_refresh")
            .on("kind", Sort.Direction.ASC).on("refresh_due_at", Sort.Direction.ASC));
        mongo.indexOps(STATE).ensureIndex(new Index().named("rec_state_expiry")
            .on("expires_at", Sort.Direction.ASC).expire(Duration.ZERO));
        mongo.indexOps(EVENTS).ensureIndex(new Index().named("rec_log_user")
            .on("kind", Sort.Direction.ASC).on("user_id", Sort.Direction.ASC).on("occurred_at", Sort.Direction.DESC));
        mongo.indexOps(EVENTS).ensureIndex(new Index().named("rec_log_expiry")
            .on("expires_at", Sort.Direction.ASC).expire(Duration.ZERO));
        ready = true;
    }
}
