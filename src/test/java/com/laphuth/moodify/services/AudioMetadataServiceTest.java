package com.laphuth.moodify.services;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AudioMetadataServiceTest {
    private static final String METADATA =
        "{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"302.347\"}],\"format\":{\"duration\":\"310.0\"}}";

    @Test
    void prefersAudioDurationOverVideoContainerAndKeepsMilliseconds() {
        assertThat(AudioMetadataService.parseDuration(METADATA)).isEqualTo(302347);
    }

    @Test
    void fallsBackToContainerForMp3AndFlacWithoutStreamDuration() {
        assertThat(AudioMetadataService.parseDuration(
            "{\"streams\":[{\"codec_type\":\"audio\"}],\"format\":{\"duration\":\"302.347\"}}"))
            .isEqualTo(302347);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "not-json", "{}", "{\"streams\":[],\"format\":{\"duration\":10}}",
        "{\"streams\":[{\"codec_type\":\"video\",\"duration\":10}]}"})
    void rejectsMissingAudioOrMalformedMetadata(String output) {
        assertThatThrownBy(() -> AudioMetadataService.parseDuration(output))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "NaN", "Infinity", "2147484", "N/A"})
    void rejectsInvalidOrOverflowingDuration(String duration) {
        assertThatThrownBy(() -> AudioMetadataService.parseDuration(
            "{\"streams\":[{\"codec_type\":\"audio\",\"duration\":\"" + duration + "\"}]}"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void probesBytesInTemporaryFileAndDeletesIt() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(15, TimeUnit.SECONDS)).thenReturn(true);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(METADATA.getBytes(StandardCharsets.UTF_8)));
        Path[] temporary = new Path[1];
        AudioMetadataService service = new AudioMetadataService("ffprobe") {
            @Override Process startProbe(Path file) throws IOException {
                temporary[0] = file;
                assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3);
                return process;
            }
        };
        assertThat(service.durationMs(new MockMultipartFile("audioFile", new byte[]{1, 2, 3}))).isEqualTo(302347);
        assertThat(temporary[0]).doesNotExist();
    }

    @Test
    void stopsStalledProbeAndCleansTemporaryFile() throws Exception {
        Process process = mock(Process.class);
        when(process.isAlive()).thenReturn(true);
        Path[] temporary = new Path[1];
        AudioMetadataService service = new AudioMetadataService("ffprobe") {
            @Override Process startProbe(Path file) { temporary[0] = file; return process; }
        };
        assertThatThrownBy(() -> service.durationMs(new MockMultipartFile("audioFile", new byte[]{1})))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(process).destroyForcibly();
        assertThat(temporary[0]).doesNotExist();
    }

    @Test
    void reportsUnavailableProbeInsteadOfSavingZeroDuration() {
        AudioMetadataService service = new AudioMetadataService("ffprobe") {
            @Override Process startProbe(Path file) throws IOException { throw new IOException("not installed"); }
        };
        assertThatThrownBy(() -> service.durationMs(new MockMultipartFile("audioFile", new byte[]{1})))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void readsRealWavMp3FlacAndM4aWhenMediaToolsAreConfigured(@TempDir Path directory) throws Exception {
        String ffprobe = System.getenv("FFPROBE_PATH");
        String ffmpeg = System.getenv("FFMPEG_PATH");
        assumeTrue(ffprobe != null && ffmpeg != null, "Set FFPROBE_PATH and FFMPEG_PATH for real-format integration test");
        AudioMetadataService service = new AudioMetadataService(ffprobe);
        for (String extension : new String[]{"wav", "mp3", "flac", "m4a"}) {
            Path audio = directory.resolve("test." + extension);
            Process generator = new ProcessBuilder(ffmpeg, "-v", "error", "-f", "lavfi", "-i",
                "sine=frequency=440:duration=2.5", "-y", audio.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            try {
                assertThat(generator.waitFor(15, TimeUnit.SECONDS)).isTrue();
                assertThat(generator.exitValue()).isZero();
                int duration = service.durationMs(new MockMultipartFile("audioFile", "test." + extension,
                    "application/octet-stream", Files.readAllBytes(audio)));
                assertThat(duration).isBetween(2450, 2700);
            } finally { if (generator.isAlive()) generator.destroyForcibly(); }
        }
    }
}
