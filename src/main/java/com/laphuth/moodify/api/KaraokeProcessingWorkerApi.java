package com.laphuth.moodify.api;

import com.laphuth.moodify.services.KaraokeProcessingService;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/internal/karaoke/processing")
public class KaraokeProcessingWorkerApi {
    private final KaraokeProcessingService service;
    private final String key;
    public KaraokeProcessingWorkerApi(KaraokeProcessingService service, @Value("${karaoke.processing.key:}") String key) { this.service=service; this.key=key; }
    private void authorize(String supplied) {
        if (key.length()<24 || supplied==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    private String token(Document body) {
        String t=body.getString("token");
        if (t==null || !t.matches("[a-f0-9]{32}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return t;
    }
    @PostMapping("/claim") public ResponseEntity<Document> claim(@RequestHeader(value="X-Processing-Key",required=false) String supplied, @RequestBody Document body) {
        authorize(supplied);
        if (!(body.get("capacity") instanceof java.util.Map<?,?> raw)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        Document capacity = new Document(); raw.forEach((k,v) -> capacity.put(String.valueOf(k),v));
        Document j=service.claim(token(body),capacity); return j==null ? ResponseEntity.noContent().build() : ResponseEntity.ok(j);
    }
    @PostMapping("/jobs/{id}/heartbeat") public Document heartbeat(@RequestHeader(value="X-Processing-Key",required=false) String supplied, @PathVariable String id, @RequestBody Document body) {
        authorize(supplied); return service.heartbeat(id,token(body),body.getString("stage"));
    }
    @PostMapping("/jobs/{id}/complete") public Document complete(@RequestHeader(value="X-Processing-Key",required=false) String supplied, @PathVariable String id, @RequestBody Document body) {
        authorize(supplied); return service.complete(id,token(body));
    }
    @PostMapping("/jobs/{id}/finish") public ResponseEntity<Void> finish(@RequestHeader(value="X-Processing-Key",required=false) String supplied, @PathVariable String id, @RequestBody Document body) {
        authorize(supplied); service.finish(id,token(body),body.getString("status"),body.getString("error")); return ResponseEntity.noContent().build();
    }
}
