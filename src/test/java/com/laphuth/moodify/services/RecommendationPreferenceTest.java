package com.laphuth.moodify.services;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import static org.assertj.core.api.Assertions.*;
import com.laphuth.moodify.entities.*;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class RecommendationPreferenceTest {
    final RecommendationEngine engine=new RecommendationEngine();
    final Track a=RecommendationEngineTest.track("a","artist-a","pop",.4);
    final Track b=RecommendationEngineTest.track("b","artist-b","pop",.4);
    final RecommendationCatalog.Snapshot catalog=new RecommendationCatalog.Snapshot(Map.of("a",a,"b",b),Map.of("a","a","external-a","a","b","b"));
    Profile profile(Set<String> artists,Map<String,Double> albums,Map<String,Double> intent,Set<String> genres) {
        return new Profile(Map.of(),List.of(),Map.of(),"baseline",new PreferenceContext(artists,albums,intent,Map.of(),"test",Map.of()),genres);
    }
    List<RankedTrack> rank(Profile profile,Map<String,Set<String>> blocked) {
        return engine.rank(catalog,profile,Map.of(),new double[]{0,0},List.of(),blocked);
    }
    @Test void explicitFollowPersonalizesColdUserWithoutPretendListening() {
        Profile p=profile(Set.of("artist-b"),Map.of(),Map.of(),Set.of());
        assertThat(p.weights()).isEmpty();assertThat(p.personalized()).isTrue();
        assertThat(rank(p,Map.of()).getFirst().track().getId()).isEqualTo("b");
        assertThat(rank(p,Map.of()).getFirst().reasonCode()).isEqualTo("FOLLOWED_ARTIST");
        assertThat(rank(profile(Set.of(),Map.of(),Map.of(),Set.of()),Map.of()).stream().mapToDouble(RankedTrack::score).distinct()).hasSize(1);
    }
    @Test void blockWinsOverFollowAlbumAndSelection() {
        Profile p=profile(Set.of("artist-b"),Map.of("b",1.0),Map.of("b",1.0),Set.of());
        assertThat(rank(p,Map.of("ARTIST",Set.of("artist-b"))).stream().map(r->r.track().getId())).containsExactly("a");
        assertThat(rank(p,Map.of("TRACK",Set.of("b"))).stream().map(r->r.track().getId())).containsExactly("a");
    }
    @Test void albumPriorRespectsItsBudgetAndDoesNotAlterTrainingSignals() {
        Profile small=profile(Set.of(),Map.of("b",1.0),Map.of(),Set.of());
        Profile large=profile(Set.of(),Map.of("b",.01),Map.of(),Set.of());
        assertThat(rank(small,Map.of()).getFirst().score()).isGreaterThan(rank(large,Map.of()).getFirst().score());
        assertThat(small.weights()).isEmpty();assertThat(small.collaborativeScores()).isEmpty();
        assertThat(rank(small,Map.of()).getFirst().reasonCode()).isEqualTo("FAVORITE_ALBUM");
    }
    @Test void requestGenreConstrainsCandidatesWithoutTurningIntoSavedTaste() {
        b.setGenres(List.of("indie"));
        Profile p=profile(Set.of("artist-a"),Map.of(),Map.of(),Set.of("indie"));
        assertThat(rank(p,Map.of()).stream().map(r->r.track().getId())).containsExactly("b");
        assertThat(p.weights()).isEmpty();
        assertThat(RecommendationEngine.genres(List.of("Rap / Hip-Hop","rap"))).containsExactly("hiphop");
    }
    @Test void savedAliasesAreOneFactWhileRealListeningRemainsIndependent() {
        Instant now=Instant.now();
        var dedup=RecommendationContextService.canonicalSignals(catalog,List.of(new Signal("a",4,now,true,false),
            new Signal("external-a",4,now.minusSeconds(5),true,false),new Signal("a",2,now,false,false)));
        assertThat(engine.profile(catalog,dedup,Map.of(),"baseline",now).weights()).containsEntry("a",6.0);
    }
    @Test void immutableOwnerSurvivesRenameAndCannotBeClaimedByReusingName() {
        Playlist p=new Playlist();p.setUsername("old-name");p.setOwnerUserId(1L);
        User owner=new User();owner.setId(1L);owner.setUsername("new-name");
        User other=new User();other.setId(2L);other.setUsername("old-name");
        assertThat(RecommendationCatalog.owns(p,owner)).isTrue();
        assertThat(RecommendationCatalog.owns(p,other)).isFalse();
        assertThat(RecommendationCatalog.owns(new Playlist(),owner)).isFalse();
        assertThat(new Playlist().getIsPublic()).isFalse();
    }
    @Test void resolverDoesNotGuessAmbiguousExternalIds() {
        var map=PreferenceEntityResolver.trackAliases(List.of(new Document("_id","one").append("spotify_id","shared"),
            new Document("_id","two").append("spotify_id","shared")));
        assertThat(map).containsKeys("one","two").doesNotContainKey("shared");
    }
}
