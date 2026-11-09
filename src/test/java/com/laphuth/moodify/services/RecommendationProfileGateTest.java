package com.laphuth.moodify.services;

import com.laphuth.moodify.repositories.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class RecommendationProfileGateTest {
    @Test void servingRequiresAcceptedEvaluationFreshProfileAndUnexpiredModel() {
        var mongo=mock(MongoTemplate.class);
        var store=new RecommendationStore(mock(JdbcTemplate.class),mongo,mock(RecommendationPersistence.class),-420,new RecommendationRuntime(true,true,false),2000);
        Instant now=Instant.now();
        var doc=new Document("model_version","implicit-als-fixture").append("feature_schema_version","behavior-v1")
            .append("as_of",Date.from(now.minusSeconds(86400))).append("refreshed_at",Date.from(now))
            .append("training_evaluation",new Document("accepted",true)).append("collaborative_scores",Map.of("track",.8));
        when(mongo.findById("profile:1",Document.class,RecommendationPersistence.STATE)).thenReturn(doc);
        assertThat(store.collaborativeScores(1,now)).containsEntry("track",.8);
        doc.put("refreshed_at",Date.from(now.minusSeconds(2701)));
        assertThat(store.collaborativeScores(1,now)).isEmpty();
        assertThat(store.modelVersion(1,now)).isEqualTo("content-session-v1");
        doc.put("refreshed_at",Date.from(now));doc.put("training_evaluation",new Document("accepted",false));
        assertThat(store.collaborativeScores(1,now)).isEmpty();
        doc.put("training_evaluation",new Document("accepted",true));doc.put("as_of",Date.from(now.minusSeconds(259201)));
        assertThat(store.collaborativeScores(1,now)).isEmpty();
    }
}
