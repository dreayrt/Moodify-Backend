package com.laphuth.moodify.dto.emotion;

/** Aggregate catalog quality indicators for the candidates considered by mood search. */
public record MoodCatalogDiagnostics(
    int tracksWithAudioFeatures,
    int tracksWithCompleteAudioFeatures,
    int tracksWithGenreTags,
    int tracksWithMoodTags,
    double averageFeatureCoverage
) {}
