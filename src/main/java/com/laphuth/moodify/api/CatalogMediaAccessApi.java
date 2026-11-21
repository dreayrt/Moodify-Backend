package com.laphuth.moodify.api;

import com.laphuth.moodify.repositories.TrackRepository;
import com.laphuth.moodify.services.CatalogPreviewTickets;
import com.laphuth.moodify.services.TrackService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
public class CatalogMediaAccessApi {
    private final TrackRepository tracks;
    private final TrackService serving;
    private CatalogPreviewTickets previews;

    @Autowired
    public void setPreviews(CatalogPreviewTickets value) {
        this.previews = value;
    }

    public CatalogMediaAccessApi(TrackRepository tracks, TrackService serving) {
        this.tracks = tracks;
        this.serving = serving;
    }

    @GetMapping(value={"/api/catalog/media-authorize"})
    public ResponseEntity<Void> authorize(@RequestHeader(value="X-Original-URI", defaultValue="") String original) {
        String path = original.split("\\?", 2)[0];
        if (!(path.startsWith("/data/audio/") || path.startsWith("/uploads/audio/") || path.startsWith("/uploads/licenses/"))) {
            return ResponseEntity.status(403).build();
        }
        if (path.contains("..") || path.contains("%") || path.contains("\\")) {
            return ResponseEntity.status(403).build();
        }
        String ticket = UriComponentsBuilder.fromUriString("http://local" + original).build().getQueryParams().getFirst("preview");
        if (this.previews != null && this.previews.valid(path, ticket)) {
            return ResponseEntity.noContent().build();
        }
        if (path.startsWith("/uploads/licenses/")) {
            return ResponseEntity.status(403).build();
        }
        boolean allowed = this.tracks.findByLocalPathIn(List.of(path, path.substring(1))).stream().anyMatch(this.serving::isTrackPubliclyAvailable);
        return allowed ? ResponseEntity.noContent().build() : ResponseEntity.status(403).build();
    }
}
