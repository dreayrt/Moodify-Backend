package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;
import java.util.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Component;

/** Expand exact verified document links, including featured credits; never join names. */
@Component
public class RecommendationArtistIdentities {
    private final MongoTemplate mongo;
    public RecommendationArtistIdentities(MongoTemplate mongo) { this.mongo=mongo; }
    public void expand(Collection<Track> tracks) {
        Set<String> requested=new HashSet<>();tracks.forEach(t->requested.addAll(RecommendationArtists.ids(t)));
        if(requested.isEmpty())return;
        List<Object> ids=new ArrayList<>(requested);
        requested.stream().filter(ObjectId::isValid).map(ObjectId::new).forEach(ids::add);
        Query query=Query.query(new Criteria().orOperator(Criteria.where("_id").in(ids),Criteria.where("spotify_id").in(requested)));
        query.fields().include("spotify_id");
        Map<String,List<Set<String>>> groups=new HashMap<>();
        for(Document artist:mongo.find(query,Document.class,"artists")) {
            Set<String> aliases=new LinkedHashSet<>();aliases.add(artist.get("_id").toString());
            String external=artist.getString("spotify_id");if(external!=null&&!external.isBlank())aliases.add(external);
            aliases.forEach(id->groups.computeIfAbsent(id,k->new ArrayList<>()).add(aliases));
        }
        for(Track track:tracks) {
            Set<String> expanded=new LinkedHashSet<>();
            if(track.getArtistIds()!=null)expanded.addAll(track.getArtistIds());
            expanded.addAll(RecommendationArtists.ids(track));
            expanded.removeIf(Objects::isNull);
            for(String id:List.copyOf(expanded)) {
                var matches=groups.getOrDefault(id,List.of());
                if(matches.size()==1)expanded.addAll(matches.getFirst());
            }
            expanded.removeIf(Objects::isNull);track.setArtistIds(new ArrayList<>(expanded));
        }
    }
}
