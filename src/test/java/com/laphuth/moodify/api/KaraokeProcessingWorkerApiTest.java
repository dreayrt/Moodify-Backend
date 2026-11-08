package com.laphuth.moodify.api;

import com.laphuth.moodify.services.KaraokeProcessingService;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KaraokeProcessingWorkerApiTest {
    @Test void internalRoutesRequireTheServerSideKey() {
        KaraokeProcessingService service=mock(KaraokeProcessingService.class);
        KaraokeProcessingWorkerApi api=new KaraokeProcessingWorkerApi(service,"secret-key-longer-than-twenty-four");
        assertEquals(401,assertThrows(ResponseStatusException.class,()->api.claim(null,new Document())).getStatusCode().value());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->api.complete("wrong","a".repeat(24),new Document())).getStatusCode().value());
        verifyNoInteractions(service);
    }
    @Test void nestedJsonCapacityMapsAreAccepted() {
        KaraokeProcessingService service=mock(KaraokeProcessingService.class); String key="secret-key-longer-than-twenty-four";
        KaraokeProcessingWorkerApi api=new KaraokeProcessingWorkerApi(service,key);
        Document request=new Document("token","a".repeat(32)).append("capacity",java.util.Map.of("available",true));
        assertEquals(204,api.claim(key,request).getStatusCode().value());
        verify(service).claim(eq("a".repeat(32)),eq(new Document("available",true)));
    }
}
