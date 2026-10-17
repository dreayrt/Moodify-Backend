package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.Track;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;

@Service
public class AudioAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AudioAnalysisService.class);

    private final RestClient restClient;

    public AudioAnalysisService(@Value("${audio.analysis.base-url:http://localhost:8002}") String baseUrl) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(15));
        requestFactory.setReadTimeout(Duration.ofSeconds(120)); // Cho phép tối đa 2 phút đối với file nhạc dài

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Phân tích đặc trưng âm học từ MultipartFile khi người dùng tải nhạc lên
     */
    public Track.AudioFeatures analyze(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return null;
        }

        try {
            return callAnalyzeApi(file.getBytes(), file.getOriginalFilename() != null ? file.getOriginalFilename() : "track.mp3");
        } catch (IOException e) {
            log.error("Không thể đọc byte từ file âm thanh upload: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Phân tích đặc trưng âm học từ file lưu cục bộ trên server
     */
    public Track.AudioFeatures analyzeLocalFile(File localFile) {
        if (localFile == null || !localFile.exists() || localFile.length() == 0) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(localFile)) {
            byte[] bytes = fis.readAllBytes();
            return callAnalyzeApi(bytes, localFile.getName());
        } catch (IOException e) {
            log.error("Không thể đọc byte từ file âm thanh cục bộ: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Track.AudioFeatures callAnalyzeApi(byte[] audioBytes, String filename) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource resource = new ByteArrayResource(audioBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            body.add("file", resource);

            log.info("Đang gửi file [{}] tới Audio Analysis Service (Essentia)...", filename);

            Map<String, Object> response = restClient.post()
                    .uri("/analyze")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                log.warn("Nhận phản hồi rỗng từ Audio Analysis Service");
                return null;
            }

            Track.AudioFeatures af = new Track.AudioFeatures();
            if (response.get("bpm") != null) {
                af.setBpm(Double.valueOf(response.get("bpm").toString()));
            }
            if (response.get("keySignature") != null) {
                af.setKeySignature(response.get("keySignature").toString());
            }
            if (response.get("energy") != null) {
                af.setEnergy(Double.valueOf(response.get("energy").toString()));
            }
            if (response.get("danceability") != null) {
                af.setDanceability(Double.valueOf(response.get("danceability").toString()));
            }
            if (response.get("valence") != null) {
                af.setValence(Double.valueOf(response.get("valence").toString()));
            }
            if (response.get("acousticness") != null) {
                af.setAcousticness(Double.valueOf(response.get("acousticness").toString()));
            }

            log.info("Phân tích âm học thành công cho [{}]: BPM={}, Key={}, Energy={}, Danceability={}, Valence={}, Acousticness={}",
                    filename, af.getBpm(), af.getKeySignature(), af.getEnergy(), af.getDanceability(), af.getValence(), af.getAcousticness());

            return af;

        } catch (Exception e) {
            log.warn("Không thể phân tích âm học từ Audio Analysis Service (Essentia): {}. Sử dụng cấu hình mặc định hoặc bỏ qua.", e.getMessage());
            return null;
        }
    }
}
