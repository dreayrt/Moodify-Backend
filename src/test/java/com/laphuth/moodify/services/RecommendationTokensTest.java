package com.laphuth.moodify.services;

import com.laphuth.moodify.security.RecommendationTokens;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RecommendationTokensTest {
    final RecommendationTokens tokens = new RecommendationTokens("test-only-secret-do-not-use-in-production-123456");
    TokenClaims claim(long user, String purpose, long expires) {
        return new TokenClaims(user,purpose,"item","request",EntityType.PERSONALIZED_PLAYLIST,"mix","version","track",0,expires);
    }
    @Test void preservesAllAttributionFields() {
        TokenClaims claims = claim(1,"QUEUE",Instant.now().plusSeconds(60).getEpochSecond());
        assertThat(tokens.verify(tokens.issue(claims),1,"QUEUE")).isEqualTo(claims);
    }
    @Test void cannotReuseAnotherUsersQueue() {
        String token = tokens.issue(claim(1,"QUEUE",Instant.now().plusSeconds(60).getEpochSecond()));
        assertThatThrownBy(() -> tokens.verify(token,2,"QUEUE")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void cannotUseCardTokenAsPlaybackEvidence() {
        String token = tokens.issue(claim(1,"ITEM",Instant.now().plusSeconds(60).getEpochSecond()));
        assertThatThrownBy(() -> tokens.verify(token,1,"QUEUE")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsTamperingAndExpiredTokens() {
        String token = tokens.issue(claim(1,"QUEUE",Instant.now().plusSeconds(60).getEpochSecond()));
        assertThatThrownBy(() -> tokens.verify("x"+token,1,"QUEUE")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> tokens.verify(tokens.issue(claim(1,"QUEUE",0)),1,"QUEUE")).isInstanceOf(ResponseStatusException.class);
    }
}
