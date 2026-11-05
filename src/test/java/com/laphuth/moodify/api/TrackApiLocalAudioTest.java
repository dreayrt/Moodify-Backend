package com.laphuth.moodify.api;

import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.services.AudioUrlResolver;
import com.laphuth.moodify.services.TrackService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
class TrackApiLocalAudioTest {
    @TempDir
    Path tempDir;

    @Test
    void servesExistingLocalAudioWithRangeInsteadOfRedirectingToCloud() throws Exception {
        Path audio = tempDir.resolve("local-track.mp3");
        Files.write(audio, new byte[] {1, 2, 3, 4});

        Track track = new Track();
        track.setId("local-track-id");
        track.setSpotifyId("local-track-spotify-id");
        track.setLocalPath(audio.toString());

        TrackService trackService = new TrackService(null, null) {
            @Override
            public Track getTrackEntity(String idOrSpotifyId) {
                return track;
            }

            @Override
            public boolean isTrackPubliclyAvailable(Track candidate) {
                return true;
            }
        };

        new AudioUrlResolver("https://media.example.test/", "");
        TrackApi api = new TrackApi(trackService, null, null);
        HttpHeaders headers = new HttpHeaders();
        headers.setRange(List.of(HttpRange.createByteRange(0, 0)));

        ResponseEntity<?> response = api.streamTrack(track.getId(), headers);

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals(
            MediaType.parseMediaType("audio/mpeg"),
            response.getHeaders().getContentType()
        );
        assertNull(response.getHeaders().getLocation());
        assertInstanceOf(ResourceRegion.class, response.getBody());
    }

    @Test
    void redirectsToCloudWhenNoLocalAudioFileExists() throws Exception {
        String spotifyId = "missing-" + UUID.randomUUID();
        Track track = new Track();
        track.setId("missing-local-track-id");
        track.setSpotifyId(spotifyId);
        track.setLocalPath("data/audio/" + spotifyId + ".mp3");

        TrackService trackService = new TrackService(null, null) {
            @Override
            public Track getTrackEntity(String idOrSpotifyId) {
                return track;
            }

            @Override
            public boolean isTrackPubliclyAvailable(Track candidate) {
                return true;
            }
        };

        new AudioUrlResolver("https://media.example.test/", "");
        TrackApi api = new TrackApi(trackService, null, null);

        ResponseEntity<?> response = api.streamTrack(track.getId(), new HttpHeaders());

        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertEquals(
            "https://media.example.test/data/audio/" + spotifyId + ".mp3",
            response.getHeaders().getLocation().toString()
        );
    }
}
