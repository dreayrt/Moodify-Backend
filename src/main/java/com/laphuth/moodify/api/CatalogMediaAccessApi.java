package com.laphuth.moodify.api;

import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.services.TrackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Nginx auth_request gate; the original URI is a path, never a remote fetch target. */
@RestController
public class CatalogMediaAccessApi {
    private final TrackRepository tracks;
    private final TrackService serving;
    private com.laphuth.moodify.services.CatalogPreviewTickets previews;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPreviews(com.laphuth.moodify.services.CatalogPreviewTickets value) { previews=value; }

    public CatalogMediaAccessApi(TrackRepository tracks,TrackService serving) { this.tracks=tracks;this.serving=serving; }

    @GetMapping("/api/catalog/media-authorize")
    public ResponseEntity<Void> authorize(@RequestHeader(value="X-Original-URI",defaultValue="") String original) {
        String path=original.split("\\?",2)[0];
        if(!path.startsWith("/data/audio/")&&!path.startsWith("/uploads/audio/")&&!path.startsWith("/uploads/licenses/"))return ResponseEntity.status(403).build();
        if(path.contains("..")||path.contains("%")||path.contains("\\"))return ResponseEntity.status(403).build();
        String ticket=org.springframework.web.util.UriComponentsBuilder.fromUriString("http://local"+original).build().getQueryParams().getFirst("preview");
        if(previews!=null&&previews.valid(path,ticket))return ResponseEntity.noContent().build();
        if(path.startsWith("/uploads/licenses/"))return ResponseEntity.status(403).build();
        boolean allowed=tracks.findByLocalPathIn(List.of(path,path.substring(1))).stream().anyMatch(serving::isTrackPubliclyAvailable);
        return allowed ? ResponseEntity.noContent().build() : ResponseEntity.status(403).build();
    }
}
