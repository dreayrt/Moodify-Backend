package com.laphuth.moodify.services;

import static com.laphuth.moodify.dto.recommendation.RecommendationTypes.*;
import com.laphuth.moodify.entities.Track;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Deterministic content/session baseline; consumes independently trained CF scores when available. */
@Component
public class RecommendationEngine {
    public Profile profile(RecommendationCatalog.Snapshot catalog, List<Signal> signals,
                           Map<String, Double> collaborative, String modelVersion, Instant now) {
        Map<String, Double> weights = new HashMap<>();
        List<String> recent = new ArrayList<>();
        for (Signal signal : signals) {
            String id = catalog.canonical(signal.trackId());
            if (!catalog.tracks().containsKey(id)) continue;
            weights.merge(id, signal.weight(), Double::sum);
            if (!signal.saved() && !signal.earlySkip() && signal.weight() > 0 &&
                signal.at().isAfter(now.minus(Duration.ofHours(2))) && !recent.contains(id) && recent.size() < 10) recent.add(id);
        }
        weights.replaceAll((id, score) -> Math.clamp(score, -2.0, 10.0));
        return new Profile(weights, recent, collaborative, modelVersion);
    }

    public List<RankedTrack> rank(RecommendationCatalog.Snapshot catalog, Profile profile,
        Map<String, double[]> rawTrend, double[] activeListeners, List<Campaign> campaigns,
        Map<String, Set<String>> blocked) {
        List<RankedTrack> result = new ArrayList<>();
        List<Track> positive = profile.weights().entrySet().stream().filter(e -> e.getValue() > 0)
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(30)
            .map(e -> catalog.resolve(e.getKey())).filter(Objects::nonNull).toList();
        List<Track> recent = profile.recentTrackIds().stream().map(catalog::resolve).filter(Objects::nonNull).toList();
        for (Track track : catalog.tracks().values()) {
            if(!profile.requestedGenres().isEmpty()&&Collections.disjoint(genres(track.getGenres()),profile.requestedGenres()))continue;
            if (blocked.getOrDefault("TRACK", Set.of()).contains(track.getId()) ||
                (track.getArtistSpotifyId() != null && blocked.getOrDefault("ARTIST", Set.of()).contains(track.getArtistSpotifyId()))) continue;
            double affinity = affinity(track, positive, profile.weights());
            double session = recent.isEmpty() ? affinity : recent.stream().mapToDouble(t -> similarity(track, t)).average().orElse(0);
            double[] counts = rawTrend.get(track.getId());
            if (counts == null && track.getSpotifyId() != null) counts = rawTrend.get(track.getSpotifyId());
            if (counts == null) counts = new double[2];
            double trend = trend(counts[0], counts[1], activeListeners[0], activeListeners[1]);
            double popularity = Math.min(1.0, Math.log1p(counts[0]) / Math.log(51));
            double cf = Math.clamp(profile.collaborativeScores().getOrDefault(track.getId(), 0.0), 0, 1);
            double campaign = campaignFit(EntityType.TRACK, track.getId(), track.getGenres(), campaigns);
            boolean followed=track.getArtistSpotifyId()!=null&&profile.preferences().followedArtists().contains(track.getArtistSpotifyId());
            double artistPrior=followed?.12:0;
            double albumPrior=.08*Math.clamp(profile.preferences().albumTrackPriors().getOrDefault(track.getId(),0.0),0,1);
            double intent=.04*Math.max(profile.preferences().intentTracks().getOrDefault(track.getId(),0.0),
                track.getArtistSpotifyId()==null?0:profile.preferences().intentArtists().getOrDefault(track.getArtistSpotifyId(),0.0));
            double score = positive.isEmpty()
                ? 0.45 * popularity + 0.25 * featureCoverage(track) + 0.2 * trend + 0.1 * campaign
                : 0.52 * affinity + 0.18 * cf + 0.15 * session + 0.08 * trend + 0.07 * campaign;
            score+=artistPrior+albumPrior+intent;
            if (profile.weights().getOrDefault(track.getId(), 0.0) < 0) score -= 0.15;
            if (profile.recentTrackIds().contains(track.getId())) score -= 0.1;
            String source = campaign > 0 ? "CAMPAIGN" : trend > 0.15 ? "TREND" : followed ? "FOLLOWED_ARTIST" : albumPrior>0 ? "FAVORITE_ALBUM" : intent>0 ? "SEARCH_INTENT" : cf > affinity && cf > 0 ? "COLLABORATIVE" :
                positive.isEmpty() ? "CATALOG" : "CONTENT";
            String reason = switch (source) {
                case "CAMPAIGN" -> "Thuộc tuyển chọn đang diễn ra";
                case "TREND" -> "Đang tăng lượt nghe trong 24 giờ";
                case "COLLABORATIVE" -> "Dựa trên những mẫu nghe tương tự";
                case "FOLLOWED_ARTIST" -> "Từ nghệ sĩ bạn đang theo dõi";
                case "FAVORITE_ALBUM" -> "Từ album bạn đã yêu thích";
                case "SEARCH_INTENT" -> "Gần với nội dung bạn vừa chủ động chọn";
                case "CONTENT" -> "Gần với những bài bạn thường nghe và lưu";
                default -> "Khám phá từ các bài đủ điều kiện trong thư viện";
            };
            result.add(new RankedTrack(track, score, source, source, reason));
        }
        result.sort(Comparator.comparingDouble(RankedTrack::score).reversed().thenComparing(r -> r.track().getId()));
        return result;
    }

    private double affinity(Track track, List<Track> positive, Map<String, Double> weights) {
        if (positive.isEmpty()) return 0;
        // Top-three neighborhoods preserve multiple tastes instead of averaging all audio into one centroid.
        return positive.stream().mapToDouble(seed -> similarity(track, seed) *
            (0.5 + 0.5 * Math.min(1, weights.getOrDefault(seed.getId(), 0.0) / 4)))
            .boxed().sorted(Comparator.reverseOrder()).limit(3).mapToDouble(Double::doubleValue).average().orElse(0);
    }

    public double similarity(Track left, Track right) {
        if (Objects.equals(left.getId(), right.getId())) return 1;
        Set<String> a = genres(left.getGenres()), b = genres(right.getGenres());
        Set<String> intersection = new HashSet<>(a); intersection.retainAll(b);
        Set<String> union = new HashSet<>(a); union.addAll(b);
        double genre = union.isEmpty() ? 0 : intersection.size() / (double) union.size();
        double artist = left.getArtistSpotifyId() != null && left.getArtistSpotifyId().equals(right.getArtistSpotifyId()) ? 1 : 0;
        double audio = audioSimilarity(left.getAudioFeatures(), right.getAudioFeatures());
        return 0.35 * genre + 0.25 * artist + 0.4 * audio;
    }

    public double audioSimilarity(Track.AudioFeatures a, Track.AudioFeatures b) {
        if (a == null || b == null) return 0;
        Double[] left = {a.getEnergy(), a.getValence(), a.getDanceability(), a.getAcousticness(), a.getBpm()};
        Double[] right = {b.getEnergy(), b.getValence(), b.getDanceability(), b.getAcousticness(), b.getBpm()};
        double distance = 0; int count = 0;
        for (int i = 0; i < left.length; i++) {
            if (left[i] == null || right[i] == null || !Double.isFinite(left[i]) || !Double.isFinite(right[i])) continue;
            if (i < 4 && (left[i] < 0 || left[i] > 1 || right[i] < 0 || right[i] > 1)) continue;
            if (i == 4 && (left[i] <= 0 || right[i] <= 0)) continue;
            double delta = Math.min(1, Math.abs(left[i] - right[i]) / (i == 4 ? 120 : 1));
            distance += delta * delta; count++;
        }
        return count == 0 ? 0 : (1 - Math.sqrt(distance / count)) * (count / 5.0);
    }

    public double featureCoverage(Track track) {
        Track.AudioFeatures a = track.getAudioFeatures();
        if (a == null) return 0;
        return java.util.stream.Stream.of(a.getEnergy(), a.getValence(), a.getDanceability(), a.getAcousticness(), a.getBpm())
            .filter(Objects::nonNull).filter(Double::isFinite).count() / 5.0;
    }

    public static double trend(double recent, double previous, double activeRecent, double activePrevious) {
        if (recent < 3 || activeRecent <= 0 || activePrevious <= 0) return 0;
        double currentRate = (recent + 2) / (activeRecent + 10);
        double previousRate = (previous + 2) / (activePrevious + 10);
        double growth = Math.log(currentRate / previousRate);
        return Math.clamp(growth / 2, 0, 1) * recent / (recent + 5);
    }

    public double campaignFit(EntityType type, String id, List<String> itemGenres, List<Campaign> campaigns) {
        Set<String> normalized = genres(itemGenres);
        return campaigns.stream().filter(Campaign::active)
            .filter(c -> c.genres().isEmpty() || !Collections.disjoint(normalized, genres(c.genres())))
            .filter(c -> c.targets().stream().anyMatch(t -> t.entityType() == type && t.entityId().equals(id)))
            .mapToDouble(c -> 0.5 + c.priority() / 200.0).max().orElse(0);
    }

    public List<RankedTrack> diversify(List<RankedTrack> ranked, int limit, int maxPerArtist) {
        List<RankedTrack> selected = new ArrayList<>();
        Set<String> ids = new HashSet<>(); Map<String, Integer> artists = new HashMap<>();
        append(selected, ids, artists, ranked, limit, maxPerArtist);
        return selected;
    }

    private void append(List<RankedTrack> selected, Set<String> ids, Map<String, Integer> artists,
                        List<RankedTrack> candidates, int targetSize, int maxPerArtist) {
        for (RankedTrack candidate : candidates) {
            String artist = Objects.toString(candidate.track().getArtistSpotifyId(), candidate.track().getArtistName());
            if (selected.size() >= targetSize) break;
            if (ids.contains(candidate.track().getId()) || artists.getOrDefault(artist, 0) >= maxPerArtist) continue;
            ids.add(candidate.track().getId()); artists.merge(artist, 1, Integer::sum); selected.add(candidate);
        }
    }

    public List<RankedTrack> mix(List<RankedTrack> ranked, Profile profile, MixType type, int limit,
                               Set<String> previousIds) {
        Comparator<RankedTrack> order = Comparator.comparingDouble((RankedTrack r) -> r.score() -
            (previousIds.contains(r.track().getId()) ? (type == MixType.WEEKLY_DISCOVERY ? 0.3 : 0.06) : 0)).reversed()
            .thenComparing(r -> r.track().getId());
        List<RankedTrack> sorted = ranked.stream().sorted(order).toList();
        List<RankedTrack> fresh = sorted.stream().filter(r -> !profile.weights().containsKey(r.track().getId())).toList();
        List<RankedTrack> selected = new ArrayList<>(); Set<String> ids = new HashSet<>();
        Map<String, Integer> artists = new HashMap<>();
        int perArtist = Math.max(2, (int)Math.ceil(limit / 10.0));
        if (type == MixType.WEEKLY_DISCOVERY) append(selected, ids, artists, fresh, limit * 3 / 4, perArtist);
        else if (type == MixType.TREND_FOR_YOU) append(selected, ids, artists,
            sorted.stream().filter(r -> "TREND".equals(r.source())).toList(), limit / 2, perArtist);
        else append(selected, ids, artists, sorted, limit / 2, perArtist);
        append(selected, ids, artists, sorted.stream().filter(r -> "TREND".equals(r.source())).toList(), limit * 7 / 10, perArtist);
        append(selected, ids, artists, sorted.stream().filter(r -> "CAMPAIGN".equals(r.source())).toList(), limit * 8 / 10, perArtist);
        append(selected, ids, artists, fresh, limit, perArtist);
        append(selected, ids, artists, sorted, limit, perArtist);
        // A smaller honest mix is preferable to repeating tracks or dropping artist/eligibility constraints.
        return sequence(selected);
    }

    private List<RankedTrack> sequence(List<RankedTrack> selected) {
        if (selected.size() < 2) return selected;
        List<RankedTrack> remaining = new ArrayList<>(selected);
        List<RankedTrack> ordered = new ArrayList<>(); ordered.add(remaining.removeFirst());
        while (!remaining.isEmpty()) {
            Track previous = ordered.getLast().track();
            RankedTrack next = remaining.stream().max(Comparator.comparingDouble((RankedTrack r) ->
                r.score() + 0.12 * audioSimilarity(previous.getAudioFeatures(), r.track().getAudioFeatures()) -
                (Objects.equals(previous.getArtistSpotifyId(), r.track().getArtistSpotifyId()) ? 0.25 : 0))
                .thenComparing(r -> r.track().getId())).orElseThrow();
            ordered.add(next); remaining.remove(next);
        }
        return ordered;
    }

    public static Set<String> genres(List<String> values) {
        if (values == null) return Set.of();
        return values.stream().filter(Objects::nonNull).map(v -> Normalizer.normalize(v, Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))
            .map(v -> switch(v) {
                case "vpop","vietnamesepop" -> "pop";
                case "rap","raphiphop","hiphop" -> "hiphop";
                case "edmremix","remix","edm" -> "edm";
                default -> v;
            })
            .filter(v -> !v.isEmpty()).collect(Collectors.toSet());
    }
}
