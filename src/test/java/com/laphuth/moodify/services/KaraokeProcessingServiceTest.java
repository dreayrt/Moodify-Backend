package com.laphuth.moodify.services;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KaraokeProcessingServiceTest {
    @TempDir Path temp;
    static final String SONG="a".repeat(24), VERSION="b".repeat(24), JOB="c".repeat(24), TOKEN="d".repeat(32);
    @Test void sourceCannotEscapeMountedDirectory() throws Exception {
        Path allowed=Files.createDirectory(temp.resolve("allowed")), outside=Files.writeString(temp.resolve("outside.mp3"),"audio");
        ResponseStatusException e=assertThrows(ResponseStatusException.class,()->KaraokeProcessingService.confined(allowed,outside));
        assertEquals(400,e.getStatusCode().value());
    }
    @Test void unsafeIdentifiersAreRejected() {
        assertThrows(ResponseStatusException.class,()->KaraokeProcessingService.id("../"+SONG));
        assertThrows(ResponseStatusException.class,()->KaraokeProcessingService.id("A".repeat(24)));
    }
    @Test void jobLookupsUseTheSameBsonIdTypeAsStorage() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.findOne(any(Query.class),eq(Document.class),eq(KaraokeProcessingService.JOBS))).thenAnswer(call->{
            assertInstanceOf(org.bson.types.ObjectId.class,((Query)call.getArgument(0)).getQueryObject().get("_id"));
            return new Document("status","completed");
        });
        KaraokeProcessingService service=new KaraokeProcessingService(mongo,temp.toString(),temp.toString(),temp.toString());
        assertThrows(ResponseStatusException.class,()->service.cancel(JOB,"admin"));
    }
    @Test void historyExcludesJobsThatAreStillRunning() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.count(any(Query.class),eq(KaraokeProcessingService.JOBS))).thenAnswer(call->{
            Document query=((Query)call.getArgument(0)).getQueryObject();
            assertEquals(java.util.List.of("queued","running"),query.get("status",Document.class).get("$nin"));
            return 0L;
        });
        when(mongo.find(any(Query.class),eq(Document.class),eq(KaraokeProcessingService.JOBS))).thenReturn(java.util.List.of());
        new KaraokeProcessingService(mongo,temp.toString(),temp.toString(),temp.toString()).jobs(0,20,"history");
    }
    @Test void aDraftCannotBeResolvedForAnExistingSingingSession() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.findOne(any(Query.class),eq(Document.class),eq("karaoke_assets")))
            .thenReturn(new Document("assetVersion","e".repeat(24)));
        when(mongo.findOne(any(Query.class),eq(Document.class),eq("karaoke_asset_versions"))).thenAnswer(call->{
            String query=((Query)call.getArgument(0)).getQueryObject().toJson();
            assertTrue(query.contains("publishedAt")); assertTrue(query.contains("ready")); return null;
        });
        assertThrows(ResponseStatusException.class,()->KaraokeAssetVersions.resolve(mongo,SONG,VERSION));
    }
    @Test void aSessionCanStillResolveItsPreviouslyPublishedVersion() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        Document historical=new Document("songId",SONG).append("assetVersion",VERSION).append("publishedAt",new Date());
        when(mongo.findOne(any(Query.class),eq(Document.class),eq("karaoke_assets"))).thenReturn(new Document("assetVersion","e".repeat(24)));
        when(mongo.findOne(any(Query.class),eq(Document.class),eq("karaoke_asset_versions"))).thenReturn(historical);
        assertSame(historical,KaraokeAssetVersions.resolve(mongo,SONG,VERSION));
    }
    @Test void corruptedOutputNeverCompletesOrPublishesAJob() throws Exception {
        MongoTemplate mongo=mock(MongoTemplate.class);
        Path sourceDir=Files.createDirectories(temp.resolve(".sources")); Path src=Files.writeString(sourceDir.resolve("source.wav"),"source");
        Document job=new Document("_id",JOB).append("kind","job").append("songId",SONG).append("assetVersion",VERSION)
            .append("status","running").append("leaseToken",TOKEN).append("sourceType","upload").append("sourcePath",src.toString())
            .append("sourceHash",KaraokeProcessingService.hash(src));
        when(mongo.findOne(any(Query.class),eq(Document.class),eq(KaraokeProcessingService.JOBS))).thenReturn(job);
        when(mongo.findById("__worker__",Document.class,KaraokeProcessingService.JOBS)).thenReturn(new Document("leaseToken",TOKEN));
        Path dir=Files.createDirectories(temp.resolve(SONG).resolve(VERSION));
        Files.writeString(dir.resolve("instrumental.wav"),"tampered");
        Document manifest=new Document("songId",SONG).append("status","prepared_local")
            .append("source",new Document("sha256",job.getString("sourceHash")).append("durationSeconds",2.0))
            .append("assets",new Document("instrumental",new Document("file","instrumental.wav").append("sha256","0".repeat(64)).append("bytes",8L)));
        Files.writeString(dir.resolve("karaoke_asset_manifest.json"),manifest.toJson());
        KaraokeProcessingService service=new KaraokeProcessingService(mongo,temp.toString(),temp.toString(),temp.toString());
        assertThrows(ResponseStatusException.class,()->service.complete(JOB,TOKEN));
        verify(mongo,never()).upsert(any(Query.class),any(org.springframework.data.mongodb.core.query.UpdateDefinition.class),eq(KaraokeProcessingService.VERSIONS));
        verify(mongo,never()).upsert(any(Query.class),any(org.springframework.data.mongodb.core.query.UpdateDefinition.class),eq(KaraokeProcessingService.ASSETS));
    }
    @Test void workerWithStaleLeaseCannotCompleteAnotherWorkersJob() {
        MongoTemplate mongo=mock(MongoTemplate.class);
        when(mongo.findOne(any(Query.class),eq(Document.class),eq(KaraokeProcessingService.JOBS))).thenReturn(new Document("status","running").append("leaseToken","e".repeat(32)));
        KaraokeProcessingService service=new KaraokeProcessingService(mongo,temp.toString(),temp.toString(),temp.toString());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.complete(JOB,TOKEN)).getStatusCode().value());
    }
}
