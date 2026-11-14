package com.laphuth.moodify.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/** Reads playback metadata independently of the optional Essentia analysis. */
@Service
public class AudioMetadataService {
    private static final Logger log = LoggerFactory.getLogger(AudioMetadataService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String ffprobe;

    public AudioMetadataService(@Value("${audio.metadata.ffprobe:ffprobe}") String ffprobe) {
        this.ffprobe = ffprobe;
    }

    public int durationMs(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalidAudio();
        Path temporaryFile = null;
        try {
            // Probe the actual bytes; neither the file extension nor client-supplied
            // duration is authoritative. Keep large uploads out of the Java heap.
            temporaryFile = Files.createTempFile("moodify-audio-metadata-", ".bin");
            try (var input = file.getInputStream()) {
                Files.copy(input, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Process process = startProbe(temporaryFile);
            try {
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Đọc thời lượng âm thanh quá lâu. Vui lòng thử lại.");
                }
                if (process.exitValue() != 0) throw invalidAudio();
                try (var output = process.getInputStream()) {
                    return parseDuration(new String(output.readNBytes(8192), StandardCharsets.UTF_8));
                }
            } finally {
                if (process.isAlive()) process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Đọc thời lượng âm thanh bị gián đoạn. Vui lòng thử lại.");
        } catch (IOException exception) {
            log.error("Cannot read uploaded audio metadata; check audio.metadata.ffprobe", exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Máy chủ chưa đọc được thời lượng âm thanh. Vui lòng thử lại sau.");
        } finally {
            if (temporaryFile != null) {
                try { Files.deleteIfExists(temporaryFile); }
                catch (IOException exception) { log.warn("Cannot delete audio metadata temporary file", exception); }
            }
        }
    }

    Process startProbe(Path file) throws IOException {
        return new ProcessBuilder(ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe",
            "-select_streams", "a:0", "-show_entries", "stream=codec_type,duration:format=duration",
            "-of", "json", file.toAbsolutePath().toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }

    static int parseDuration(String output) {
        try {
            JsonNode metadata = JSON.readTree(output);
            if (metadata == null) throw invalidAudio();
            JsonNode stream = metadata.path("streams").path(0);
            if (!"audio".equals(stream.path("codec_type").asText())) throw invalidAudio();
            // MP4/M4A can include video with a different length. Prefer the selected
            // audio stream; MP3/FLAC may expose duration only at container level.
            int duration = milliseconds(stream.path("duration"));
            if (duration == 0) duration = milliseconds(metadata.path("format").path("duration"));
            if (duration == 0) throw invalidAudio();
            return duration;
        } catch (IOException exception) {
            throw invalidAudio();
        }
    }

    private static int milliseconds(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) return 0;
        double seconds = value.asDouble(Double.NaN);
        double milliseconds = seconds * 1000;
        if (!Double.isFinite(milliseconds) || milliseconds < 1 || milliseconds > Integer.MAX_VALUE) return 0;
        return (int) Math.round(milliseconds);
    }

    private static ResponseStatusException invalidAudio() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Không đọc được thời lượng hợp lệ từ tệp âm thanh. Vui lòng chọn tệp nhạc khác.");
    }
}
