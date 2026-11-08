package com.laphuth.moodify.services;

import com.laphuth.moodify.repositories.RecommendationStore;
import com.laphuth.moodify.security.RecommendationTokens;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.laphuth.moodify.entities.User;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RecommendationPlaybackContextTest {
    final RecommendationCatalog catalog=mock(RecommendationCatalog.class);
    final RecommendationStore store=mock(RecommendationStore.class);
    final PersonalizedMixService mixes=mock(PersonalizedMixService.class);
    final RecommendationTokens tokens=mock(RecommendationTokens.class);
    final RecommendationService service=new RecommendationService(catalog,mock(RecommendationContextService.class),store,
        new RecommendationEngine(),mixes,tokens,mock(RecommendationPreferenceService.class),mock(PreferenceEntityResolver.class),
        new RecommendationRuntime(false,false,false));
    final User user=new User();
    final TokenClaims claims=new TokenClaims(1,"QUEUE",null,null,EntityType.PERSONALIZED_PLAYLIST,"mix","version","track",0,Long.MAX_VALUE);
    void prepare() {
        user.setId(1L);
        when(tokens.verify("signed",1,"QUEUE")).thenReturn(claims);
        when(catalog.playableTrack("external")).thenReturn(RecommendationEngineTest.track("track","artist","pop",.4));
        when(store.blocked(1)).thenReturn(Map.of());
    }
    @Test void heartbeatDoesNotLoadFullCatalogAndExistingQueueWorksWhileGenerationDisabled() {
        prepare();
        var context=service.playbackContext(user,"signed","external","OTHER",null,"WEB");
        assertThat(context.source()).isEqualTo("PLAYLIST");
        assertThat(context.sourceId()).isEqualTo("mix");
        verify(catalog,never()).snapshot();
        verify(mixes).owned(1,"mix");
    }
    @Test void blockChangesInvalidatePreviouslySignedQueueTokens() {
        prepare();when(store.blocked(1)).thenReturn(Map.of("ARTIST",Set.of("artist")));
        assertThatThrownBy(()->service.playbackContext(user,"signed","external",null,null,null))
            .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(404));
        verifyNoInteractions(mixes);
    }
    @Test void ordinaryPlaybackDoesNotReadRecommendationDatabases() {
        user.setId(1L);
        var context=service.playbackContext(user,null,"track","LIBRARY",null,"WEB");
        assertThat(context.source()).isEqualTo("LIBRARY");
        assertThat(context.claims()).isNull();
        verifyNoInteractions(catalog,store,mixes,tokens);
    }
}
