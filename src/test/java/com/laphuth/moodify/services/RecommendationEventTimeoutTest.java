package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.recommendation.RecommendationTypes;
import com.laphuth.moodify.repositories.RecommendationPersistence;
import com.laphuth.moodify.repositories.RecommendationStore;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClients;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

class RecommendationEventTimeoutTest {
    @Test void eventDeadlineBoundsARealDriverOperationAgainstAnUnresponsiveLocalSocket() throws Exception {
        // A private ephemeral socket is deliberately not a Mongo server. Never stops/shared-hosts the application DB.
        try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
            var client=MongoClients.create("mongodb://127.0.0.1:"+socket.getLocalPort()+"/?serverSelectionTimeoutMS=10000")) {
            var store=new RecommendationStore(mock(JdbcTemplate.class),new MongoTemplate(client,"recommendation_timeout_fixture"),
                mock(RecommendationPersistence.class),-420,new RecommendationRuntime(true,true,true),150);
            var claims=new RecommendationTypes.TokenClaims(1,"QUEUE",null,null,RecommendationTypes.EntityType.TRACK,
                "track",null,"track",0,Long.MAX_VALUE);
            long started=System.nanoTime();
            assertThatThrownBy(()->store.event(1,"event","PLAY_START",claims,Instant.now(),"session",1L,Map.of()))
                .isInstanceOf(MongoException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)).isLessThan(2500);
        }
    }
}
