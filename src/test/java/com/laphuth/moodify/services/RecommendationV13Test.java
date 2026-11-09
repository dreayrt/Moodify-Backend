package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.*;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import com.laphuth.moodify.security.RecommendationTokens;
import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class RecommendationV13Test {
    Track track(String id) {
        Track t=new Track();t.setId(id);t.setStatus("published");t.setVisibility("public");
        t.setModerationStatus("approved");t.setLocalPath("data/audio/"+id+".mp3");
        t.setArtistIds(List.of("internal-artist"));return t;
    }
    @Test void internalArtistsDriveSimilarityFollowAndBlock() {
        var engine=new RecommendationEngine();Track a=track("a"),b=track("b");
        assertThat(engine.similarity(a,b)).isEqualTo(.25);
        var catalog=new RecommendationCatalog.Snapshot(Map.of("a",a),Map.of());
        var prefs=new PreferenceContext(Set.of("internal-artist"),Map.of(),Map.of(),Map.of(),"",Map.of());
        var profile=new Profile(Map.of(),List.of(),Map.of(),"baseline",prefs,Set.of());
        var ranked=engine.rank(catalog,profile,Map.of(),new double[]{0,0},List.of(),Map.of());
        assertThat(ranked).hasSize(1);assertThat(ranked.getFirst().source()).isEqualTo("FOLLOWED_ARTIST");
        assertThat(engine.rank(catalog,profile,Map.of(),new double[]{0,0},List.of(),Map.of("ARTIST",Set.of("internal-artist")))).isEmpty();
    }
    @Test void featuredArtistBlockWinsAndDiversificationCountsEveryVerifiedArtist() {
        Track a=track("a"),b=track("b");a.setArtistIds(List.of("main-a","featured"));b.setArtistIds(List.of("main-b","featured"));
        assertThat(RecommendationArtists.blocked(a,Set.of("featured"))).isTrue();
        var ranked=List.of(new RankedTrack(a,1,"CONTENT","CONTENT",""),new RankedTrack(b,.9,"CONTENT","CONTENT",""));
        assertThat(new RecommendationEngine().diversify(ranked,10,1)).hasSize(1);
    }
    @Test void configurationCannotBypassStrictLicenseOrUnsafeAudio() {
        Track t=track("a");t.setCatalogMigrationBatch("allowed");
        assertThat(RecommendationCatalog.eligible(t,null,LocalDate.now(),false,Set.of("allowed"))).isFalse();
        SongLicense l=new SongLicense();l.setStatus(LicenseStatus.ACTIVE);
        for(String path:List.of("null","undefined","data/../private.mp3")) {
            t.setLocalPath(path);assertThat(RecommendationCatalog.eligible(t,l,LocalDate.now(),false)).isFalse();
        }
    }
    @Test void expiredSignedTokenHasSpecificCodeButWrongOwnerDoesNot() {
        var tokens=new RecommendationTokens("a-strong-test-fixture-secret-with-48-characters-123456");
        String token=tokens.issue(new TokenClaims(1,"ITEM","item","request",EntityType.TRACK,"track",null,null,null,Instant.now().minusSeconds(5).getEpochSecond()));
        assertThatThrownBy(()->tokens.verify(token,1,"ITEM")).isInstanceOfSatisfying(ResponseStatusException.class,
            e->assertThat(e.getBody().getProperties()).containsEntry("code","RECOMMENDATION_TOKEN_EXPIRED"));
        assertThatThrownBy(()->tokens.verify(token,2,"ITEM")).isInstanceOfSatisfying(ResponseStatusException.class,
            e->assertThat(e.getReason()).isEqualTo("Invalid or expired recommendation context"));
    }
}
