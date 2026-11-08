package com.laphuth.moodify.api;

import com.laphuth.moodify.services.KaraokeProcessingService;
import org.bson.Document;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/karaoke")
public class AdminKaraokeApi {
    private final KaraokeProcessingService service;
    public AdminKaraokeApi(KaraokeProcessingService service) { this.service = service; }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> failure(org.springframework.web.server.ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("message", java.util.Objects.toString(error.getReason(), "Yêu cầu không hợp lệ.")));
    }
    @GetMapping("/resources") public Map<String,Object> resources(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) String query, @RequestParam(required=false) String status) { return service.resources(page,size,query,status); }
    @GetMapping("/resources/{songId}") public Map<String,Object> detail(@PathVariable String songId) { return service.detail(songId); }
    @GetMapping("/jobs") public Map<String,Object> jobs(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
        @RequestParam(required=false) String status) { return service.jobs(page,size,status); }
    @PostMapping(value="/jobs", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Document> create(@RequestParam String songId, @RequestPart(required=false) MultipartFile file, Authentication auth) {
        return ResponseEntity.accepted().body(service.create(songId,file,auth.getName(),null));
    }
    @PostMapping("/jobs/{jobId}/cancel") public Map<String,Boolean> cancel(@PathVariable String jobId, Authentication auth) { service.cancel(jobId,auth.getName()); return Map.of("success",true); }
    @PostMapping("/jobs/{jobId}/retry") public ResponseEntity<Document> retry(@PathVariable String jobId, Authentication auth) { return ResponseEntity.accepted().body(service.retry(jobId,auth.getName())); }
    @PostMapping("/resources/{songId}/versions/{version}/publish") public Document publish(@PathVariable String songId, @PathVariable String version, Authentication auth) { return service.publish(songId,version,auth.getName()); }
    @GetMapping("/resources/{songId}/source") public ResponseEntity<FileSystemResource> source(@PathVariable String songId, @RequestParam(required=false) String jobId) {
        Path path=service.sourceFile(songId,jobId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaTypeFactory.getMediaType(path.getFileName().toString()).orElse(MediaType.APPLICATION_OCTET_STREAM))
            .body(new FileSystemResource(path));
    }
}
