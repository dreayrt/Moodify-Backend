// Read-only audit for the currently selected MongoDB database.
// Run with an authenticated mongosh session and the `tracks` collection selected.
// This script never updates or deletes documents.

const collection = db.getCollection("tracks");
const report = {
  collection: "tracks",
  totalTracks: 0,
  withGenres: 0,
  withGenresRaw: 0,
  withMoodTags: 0,
  withAudioFeatures: 0,
  withCompleteMoodFeatures: 0,
  genreRawMappingsWithoutOverlap: 0,
  genreFrequencies: {},
  genreRawFrequencies: {},
  moodTagFrequencies: {},
  mappingMismatchSamples: [],
  tracksMissingMoodTagSamples: [],
};

function normalizedTags(values) {
  if (!Array.isArray(values)) return [];
  return values
    .filter((value) => typeof value === "string" && value.trim().length > 0)
    .map((value) => {
      const normalized = value
        .normalize("NFD")
        .replace(/\p{Diacritic}/gu, "")
        .toLowerCase()
        .replace(/[^a-z0-9]/g, "");
      if (normalized === "vpop" || normalized === "vietnamesepop") return "pop";
      if (normalized === "rhythmandblues") return "rnb";
      return normalized;
    });
}

function countTags(values, frequencyMap) {
  if (!Array.isArray(values)) return;
  values.forEach((value) => {
    if (typeof value !== "string" || !value.trim()) return;
    frequencyMap[value.trim()] = (frequencyMap[value.trim()] || 0) + 1;
  });
}

function isValidNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

collection.find(
  {},
  {
    spotify_id: 1,
    name: 1,
    genres: 1,
    genres_raw: 1,
    mood_tags: 1,
    audio_features: 1,
  }
).forEach((track) => {
  report.totalTracks += 1;

  const genres = Array.isArray(track.genres) ? track.genres : [];
  const genresRaw = Array.isArray(track.genres_raw) ? track.genres_raw : [];
  const moodTags = Array.isArray(track.mood_tags) ? track.mood_tags : [];
  const features = track.audio_features || {};

  if (genres.some((tag) => typeof tag === "string" && tag.trim())) report.withGenres += 1;
  if (genresRaw.some((tag) => typeof tag === "string" && tag.trim())) report.withGenresRaw += 1;
  if (moodTags.some((tag) => typeof tag === "string" && tag.trim())) report.withMoodTags += 1;
  countTags(genres, report.genreFrequencies);
  countTags(genresRaw, report.genreRawFrequencies);
  countTags(moodTags, report.moodTagFrequencies);

  const hasAnyMoodFeature = [features.valence, features.energy, features.danceability,
    features.acousticness, features.bpm, features.key_signature]
    .some((value) => typeof value === "string" ? value.trim().length > 0 : isValidNumber(value));
  const hasAllMoodFeatures = isValidNumber(features.valence)
    && isValidNumber(features.energy)
    && isValidNumber(features.danceability)
    && isValidNumber(features.acousticness)
    && isValidNumber(features.bpm)
    && typeof features.key_signature === "string"
    && features.key_signature.trim().length > 0;
  if (hasAnyMoodFeature) report.withAudioFeatures += 1;
  if (hasAllMoodFeatures) report.withCompleteMoodFeatures += 1;

  const normalizedGenres = normalizedTags(genres);
  const normalizedRawGenres = normalizedTags(genresRaw);
  const hasOverlap = normalizedGenres.some((tag) => normalizedRawGenres.includes(tag));
  if (normalizedGenres.length > 0 && normalizedRawGenres.length > 0 && !hasOverlap) {
    report.genreRawMappingsWithoutOverlap += 1;
    if (report.mappingMismatchSamples.length < 20) {
      report.mappingMismatchSamples.push({
        spotify_id: track.spotify_id || null,
        name: track.name || null,
        genres,
        genres_raw: genresRaw,
      });
    }
  }

  if (moodTags.length === 0 && report.tracksMissingMoodTagSamples.length < 20) {
    report.tracksMissingMoodTagSamples.push({
      spotify_id: track.spotify_id || null,
      name: track.name || null,
      genres,
      genres_raw: genresRaw,
      hasAudioFeatures: hasAnyMoodFeature,
    });
  }
});

print(JSON.stringify(report, null, 2));
