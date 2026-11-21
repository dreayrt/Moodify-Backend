package com.laphuth.moodify.services;

import com.laphuth.moodify.entities.SongLicense;
import com.laphuth.moodify.entities.Track;
import com.laphuth.moodify.entities.enums.LicenseStatus;
import com.laphuth.moodify.repositories.SongLicenseRepository;
import com.laphuth.moodify.repositories.TrackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@ConditionalOnProperty(name = "app.maintenance.enabled", havingValue = "true", matchIfMissing = true)
public class SongLicenseLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(SongLicenseLifecycleScheduler.class);

    private final SongLicenseRepository songLicenseRepository;
    private final TrackRepository trackRepository;
    private org.springframework.data.mongodb.core.MongoTemplate mongo;
    private boolean demoCatalogEnabled;
    @org.springframework.beans.factory.annotation.Autowired
    public void setMongo(org.springframework.data.mongodb.core.MongoTemplate value) { mongo=value; }
    @org.springframework.beans.factory.annotation.Value("${recommendations.demo-catalog-enabled:false}")
    public void setDemoCatalogEnabled(boolean value) { demoCatalogEnabled=value; }


    public SongLicenseLifecycleScheduler(
        SongLicenseRepository songLicenseRepository,
        TrackRepository trackRepository
    ) {
        this.songLicenseRepository = songLicenseRepository;
        this.trackRepository = trackRepository;
    }

    /** Reconciles expired licenses once at startup to catch up after downtime. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("[License Lifecycle] Khởi chạy kiểm tra và đồng bộ trạng thái bản quyền bài hát ban đầu...");
        int count = processExpiredLicenses();
        log.info("[License Lifecycle] Hoàn tất kiểm tra ban đầu. Đã xử lý {} bài hát hết hạn hợp đồng.", count);
    }

    /**
     * Chạy định kỳ vào 00:05 mỗi ngày theo múi giờ Việt Nam (Asia/Ho_Chi_Minh)
     */
    @Scheduled(cron = "${license.expiry.cron:0 5 0 * * ?}", zone = "Asia/Ho_Chi_Minh")
    public void scheduledExpiryCheck() {
        log.info("[License Lifecycle] Bắt đầu tiến trình định kỳ kiểm tra các hợp đồng bản quyền hết hạn...");
        int count = processExpiredLicenses();
        log.info("[License Lifecycle] Kết thúc tiến trình định kỳ. Đã vô hiệu hóa {} bài hát hết hạn.", count);
    }

    /**
     * Xử lý đồng bộ các hợp đồng hết hạn sang MongoDB
     * @return Số lượng bản ghi đã được xử lý
     */
    public int processExpiredLicenses() {
        LocalDate today=LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        List<SongLicense> expiredLicenses=songLicenseRepository.findAllExpiredLicenses(today);
        java.util.Set<String> affected=new java.util.LinkedHashSet<>();
        for(SongLicense license:expiredLicenses) {
            if(license.getStatus()==LicenseStatus.ACTIVE && license.getExpiryDate()!=null && license.getExpiryDate().isBefore(today)) {
                songLicenseRepository.expireIfActive(license.getId(),today);
            }
            if(license.getTrackId()!=null)affected.add(license.getTrackId());
        }
        int processed=0;
        for(String raw:affected) {
            try {
                Track track=trackRepository.findById(raw).or(()->trackRepository.findBySpotifyId(raw)).orElse(null);
                if(track==null)continue;
                java.util.List<String> ids=new java.util.ArrayList<>(java.util.List.of(track.getId()));
                if(track.getSpotifyId()!=null)ids.add(track.getSpotifyId());
                SongLicense current=CatalogLicensePolicy.select(track,songLicenseRepository.findByTrackIdIn(ids),today);
                if(CatalogLicensePolicy.effective(current,today) && CatalogLicensePolicy.evidenceReady(track,current,demoCatalogEnabled))continue;
                if("archived".equalsIgnoreCase(track.getStatus()) && "private".equalsIgnoreCase(track.getVisibility()))continue;
                if(mongo==null)throw new IllegalStateException("Partial Mongo update service unavailable");
                var query=org.springframework.data.mongodb.core.query.Query.query(
                    org.springframework.data.mongodb.core.query.Criteria.where("_id").is(track.getId())
                        .and("status").is(track.getStatus()).and("visibility").is(track.getVisibility()));
                var update=new org.springframework.data.mongodb.core.query.Update().set("status","archived").set("visibility","private")
                    .set("rights_hold",java.util.Map.of("reason","NO_EFFECTIVE_LICENSE","at",Instant.now()))
                    .set("updated_at",Instant.now());
                processed+=mongo.updateFirst(query,update,Track.class).getModifiedCount();
            } catch(Exception failure) {
                // SQL expiry is durable. All expired rows are revisited, so a Mongo
                // failure is retried next time; serving already denies by live dates.
                log.error("[License Lifecycle] Retry required for {}",raw,failure);
            }
        }
        return processed;
    }
}
