package com.laphuth.moodify.services;

import com.laphuth.moodify.dto.karaoke.rooms.CreateKaraokeRoomRequest;
import com.laphuth.moodify.dto.karaoke.rooms.JoinKaraokeRoomRequest;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomCreatedResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomDetailResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomMemberResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomPlaybackResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomPlaybackTrackResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomQueueItemResponse;
import com.laphuth.moodify.dto.karaoke.rooms.KaraokeRoomSummaryResponse;
import com.laphuth.moodify.dto.karaoke.rooms.QueueKaraokeSongRequest;

import com.laphuth.moodify.entities.KaraokeRoom;
import com.laphuth.moodify.entities.KaraokePlaybackStatus;
import com.laphuth.moodify.entities.KaraokeRoomMember;
import com.laphuth.moodify.entities.KaraokeRoomMemberId;
import com.laphuth.moodify.entities.KaraokeRoomQueueItem;
import com.laphuth.moodify.entities.KaraokeRoomVisibility;
import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.enums.UserStatus;
import com.laphuth.moodify.repositories.KaraokeRoomMemberRepository;
import com.laphuth.moodify.repositories.KaraokeRoomQueueRepository;
import com.laphuth.moodify.repositories.KaraokeRoomRepository;
import com.laphuth.moodify.repositories.KaraokeRoomSummaryProjection;
import com.laphuth.moodify.repositories.UserRepository;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class KaraokeRoomService {
    private static final int MAX_ROOM_CAPACITY = 6;
    private static final String TRACKS = "tracks";
    private static final String ASSETS = "karaoke_assets";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final KaraokeRoomRepository roomRepository;
    private final KaraokeRoomMemberRepository memberRepository;
    private final KaraokeRoomQueueRepository queueRepository;
    private final UserRepository userRepository;
    private final MongoTemplate mongo;
    private final KaraokeRoomRealtimeGateway realtimeGateway;

    public KaraokeRoomService(KaraokeRoomRepository roomRepository,
                              KaraokeRoomMemberRepository memberRepository,
                              KaraokeRoomQueueRepository queueRepository,
                              UserRepository userRepository,
                              MongoTemplate mongo,
                              KaraokeRoomRealtimeGateway realtimeGateway) {
        this.roomRepository = roomRepository;
        this.memberRepository = memberRepository;
        this.queueRepository = queueRepository;
        this.userRepository = userRepository;
        this.mongo = mongo;
        this.realtimeGateway = realtimeGateway;
    }

    @Transactional
    public KaraokeRoomCreatedResponse createRoom(CreateKaraokeRoomRequest request, Authentication authentication) {
        User host = authenticatedUser(authentication);
        String inviteCode = request.visibility() == KaraokeRoomVisibility.PRIVATE ? createInviteCode() : null;

        KaraokeRoom room = new KaraokeRoom();
        room.setName(request.name().trim());
        room.setHostUser(host);
        room.setMaxMembers(request.maxMembers());
        room.setVisibility(request.visibility());
        room.setInviteCodeHash(inviteCode == null ? null : hashInviteCode(inviteCode));
        room.setInviteCode(inviteCode);
        room = roomRepository.saveAndFlush(room);

        KaraokeRoomMember hostMembership = new KaraokeRoomMember();
        hostMembership.setId(new KaraokeRoomMemberId(room.getId(), host.getId()));
        hostMembership.setRoom(room);
        hostMembership.setUser(host);
        hostMembership.setJoinedAt(LocalDateTime.now());
        memberRepository.save(hostMembership);

        return new KaraokeRoomCreatedResponse(room.getId(), room.getName(), room.getVisibility(),
                request.maxMembers(), inviteCode);
    }

    @Transactional(readOnly = true)
    public List<KaraokeRoomSummaryResponse> listOpenRooms() {
        return roomRepository.findOpenRoomSummaries().stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional
    public KaraokeRoomDetailResponse joinRoom(Long roomId, JoinKaraokeRoomRequest request,
                                               Authentication authentication) {
        User user = authenticatedUser(authentication);
        KaraokeRoom room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"));
        ensureOpen(room);

        KaraokeRoomMember member = memberRepository.findByRoom_IdAndUser_Id(roomId, user.getId()).orElse(null);
        boolean isHost = room.getHostUser().getId().equals(user.getId());
        if (room.getVisibility() == KaraokeRoomVisibility.PRIVATE && !isHost
                && (member == null || member.getLeftAt() != null)) {
            verifyInviteCode(room, request == null ? null : request.inviteCode());
        }

        if (member != null && member.getLeftAt() == null) {
            recoverHostIfMissing(room, user);
            ensureHostInviteCode(room, user.getId());
            return roomDetail(room, user.getId());
        }

        long activeCount = memberRepository.countByRoom_IdAndLeftAtIsNull(roomId);
        if (activeCount >= Math.min(room.getMaxMembers(), MAX_ROOM_CAPACITY)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ROOM_FULL");
        }

        if (member == null) {
            member = new KaraokeRoomMember();
            member.setId(new KaraokeRoomMemberId(room.getId(), user.getId()));
            member.setRoom(room);
            member.setUser(user);
        }
        member.setJoinedAt(LocalDateTime.now());
        member.setLeftAt(null);
        memberRepository.save(member);
        memberRepository.flush();
        recoverHostIfMissing(room, user);
        ensureHostInviteCode(room, user.getId());
        return roomDetail(room, user.getId());
    }

    @Transactional
    public KaraokeRoomDetailResponse getRoom(Long roomId, Authentication authentication) {
        User user = authenticatedUser(authentication);
        KaraokeRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"));
        ensureOpen(room);
        requireActiveMember(room, user.getId());
        ensureHostInviteCode(room, user.getId());
        return roomDetail(room, user.getId());
    }

    @Transactional
    public boolean leaveRoom(Long roomId, Authentication authentication) {
        User user = authenticatedUser(authentication);
        KaraokeRoom room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"));
        KaraokeRoomMember member = memberRepository.findByRoom_IdAndUser_Id(roomId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ROOM_MEMBER_NOT_FOUND"));
        if (member.getLeftAt() != null) return false;
        member.setLeftAt(LocalDateTime.now());
        if (room.getHostUser().getId().equals(user.getId())) transferHostOrPause(room, user.getId());
        return true;
    }

    @Transactional
    public boolean markMemberDisconnected(Long roomId, Long userId) {
        KaraokeRoom room = roomRepository.findByIdForUpdate(roomId).orElse(null);
        if (room == null) return false;
        KaraokeRoomMember member = memberRepository.findByRoom_IdAndUser_Id(roomId, userId).orElse(null);
        if (member == null || member.getLeftAt() != null) return false;
        member.setLeftAt(LocalDateTime.now());
        if (room.getHostUser().getId().equals(userId)) transferHostOrPause(room, userId);
        return true;
    }

    @Transactional
    public KaraokeRoomDetailResponse startPlayback(Long roomId, long expectedVersion, Authentication authentication) {
        User actor = authenticatedUser(authentication);
        KaraokeRoom room = lockedRoom(roomId);
        requireHost(room, actor.getId());
        requireVersion(room, expectedVersion);
        if (room.getPlaybackStatus() == KaraokePlaybackStatus.PLAYING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLAYBACK_ALREADY_RUNNING");
        }
        startNextTrack(room);
        return roomDetail(room, actor.getId());
    }

    @Transactional
    public KaraokeRoomDetailResponse pausePlayback(Long roomId, long expectedVersion, Authentication authentication) {
        User actor = authenticatedUser(authentication);
        KaraokeRoom room = lockedRoom(roomId);
        requireHost(room, actor.getId());
        requireVersion(room, expectedVersion);
        if (room.getPlaybackStatus() != KaraokePlaybackStatus.PLAYING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLAYBACK_NOT_RUNNING");
        }
        pause(room);
        room.setPlaybackVersion(room.getPlaybackVersion() + 1);
        return roomDetail(room, actor.getId());
    }

    @Transactional
    public KaraokeRoomDetailResponse resumePlayback(Long roomId, long expectedVersion, Authentication authentication) {
        User actor = authenticatedUser(authentication);
        KaraokeRoom room = lockedRoom(roomId);
        requireHost(room, actor.getId());
        requireVersion(room, expectedVersion);
        if (room.getPlaybackStatus() != KaraokePlaybackStatus.PAUSED || room.getCurrentTrackId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLAYBACK_NOT_PAUSED");
        }
        room.setPlaybackStatus(KaraokePlaybackStatus.PLAYING);
        room.setPlaybackStartedAt(LocalDateTime.now());
        room.setPlaybackVersion(room.getPlaybackVersion() + 1);
        return roomDetail(room, actor.getId());
    }

    @Transactional
    public KaraokeRoomDetailResponse skipPlayback(Long roomId, long expectedVersion, Authentication authentication) {
        User actor = authenticatedUser(authentication);
        KaraokeRoom room = lockedRoom(roomId);
        requireHost(room, actor.getId());
        requireVersion(room, expectedVersion);
        if (room.getCurrentTrackId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLAYBACK_NOT_RUNNING");
        }
        startNextTrack(room);
        return roomDetail(room, actor.getId());
    }

    @Transactional
    public boolean advanceIfExpired(Long roomId) {
        KaraokeRoom room = roomRepository.findByIdForUpdate(roomId).orElse(null);
        if (room == null || room.getClosedAt() != null
                || (room.getPlaybackStatus() != KaraokePlaybackStatus.PLAYING
                && room.getPlaybackStatus() != KaraokePlaybackStatus.PAUSED)
                || room.getCurrentTrackId() == null) return false;
        if (room.getPlaybackStatus() == KaraokePlaybackStatus.PLAYING && room.getPlaybackStartedAt() == null) return false;
        Document track = mongo.findById(new ObjectId(room.getCurrentTrackId()), Document.class, TRACKS);
        Object rawDuration = track == null ? null : track.get("duration_ms");
        if (!(rawDuration instanceof Number number) || number.longValue() <= 0) return false;
        long elapsed = room.getPlaybackPositionMs();
        if (room.getPlaybackStatus() == KaraokePlaybackStatus.PLAYING) {
            elapsed += Math.max(0, Duration.between(room.getPlaybackStartedAt(), LocalDateTime.now()).toMillis());
        }
        if (elapsed < number.longValue()) return false;
        startNextTrack(room);
        return true;
    }

    @Transactional
    public KaraokeRoomQueueItemResponse addQueueItem(Long roomId, QueueKaraokeSongRequest request,
                                                      Authentication authentication) {
        User requester = authenticatedUser(authentication);
        KaraokeRoom room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"));
        ensureOpen(room);
        requireActiveMember(room, requester.getId());
        if (queueRepository.existsByRoom_IdAndRequester_Id(roomId, requester.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "QUEUE_ALREADY_REQUESTED");
        }
        Document track = findUsableKaraokeTrack(request.trackId());

        KaraokeRoomQueueItem item = new KaraokeRoomQueueItem();
        item.setRoom(room);
        item.setRequester(requester);
        item.setTrackId(request.trackId());
        item = queueRepository.saveAndFlush(item);
        return toQueueItem(item, track, requester.getId());
    }

    @Transactional
    public boolean removeQueueItem(Long roomId, Long itemId, Authentication authentication) {
        User actor = authenticatedUser(authentication);
        KaraokeRoom room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"));
        ensureOpen(room);
        requireActiveMember(room, actor.getId());
        KaraokeRoomQueueItem item = queueRepository.findByIdAndRoom_Id(itemId, roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "QUEUE_ITEM_NOT_FOUND"));
        boolean host = room.getHostUser().getId().equals(actor.getId());
        if (!host && !item.getRequester().getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "QUEUE_ITEM_FORBIDDEN");
        }
        queueRepository.delete(item);
        return true;
    }

    @Transactional
    public boolean closeRoom(Long roomId, Authentication authentication) {
        User actor = authenticatedUser(authentication);
        KaraokeRoom room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"));
        if (!room.getHostUser().getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROOM_HOST_REQUIRED");
        }
        if (room.getClosedAt() != null) return false;
        LocalDateTime now = LocalDateTime.now();
        room.setClosedAt(now);
        pause(room);
        room.setCurrentTrackId(null);
        room.setPlaybackRunId(null);
        room.setPlaybackPositionMs(0L);
        room.setPlaybackVersion(room.getPlaybackVersion() + 1);
        memberRepository.findAllByRoom_IdAndLeftAtIsNullOrderByJoinedAtAsc(roomId)
                .forEach(member -> member.setLeftAt(now));
        queueRepository.deleteAll(queueRepository.findAllByRoom_IdOrderByCreatedAtAscIdAsc(roomId));
        return true;
    }

    @Transactional
    public boolean closeEmptyRoomsAfterInactivity() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        List<Long> expiredRoomIds = roomRepository.findEmptyOpenRoomIdsBefore(cutoff);
        if (expiredRoomIds.isEmpty()) return false;
        LocalDateTime now = LocalDateTime.now();
        roomRepository.closeRooms(expiredRoomIds, now);
        queueRepository.deleteAllForRooms(expiredRoomIds);
        return true;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void clearPresenceAfterBackendRestart() {
        // A single-instance in-memory socket registry cannot survive a process restart.
        for (Long roomId : roomRepository.findAllIdsByPlaybackStatus(KaraokePlaybackStatus.PLAYING)) {
            KaraokeRoom room = roomRepository.findByIdForUpdate(roomId).orElse(null);
            if (room == null) continue;
            pause(room);
            room.setPlaybackVersion(room.getPlaybackVersion() + 1);
        }
        memberRepository.markAllActiveAsLeft(LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public boolean isActiveMember(Long roomId, Long userId) {
        return memberRepository.findByRoom_IdAndUser_Id(roomId, userId)
                .map(member -> member.getLeftAt() == null)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isActiveMemberInOpenRoom(Long roomId, Long userId) {
        return roomId != null && userId != null
                && memberRepository.countActiveMembersInOpenRoom(roomId, userId) > 0;
    }

    @Transactional(readOnly = true)
    public KaraokeRoomDetailResponse roomDetailForUser(Long roomId, Long userId) {
        KaraokeRoom room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"));
        ensureOpen(room);
        requireActiveMember(room, userId);
        return roomDetail(room, userId);
    }

    @Transactional(readOnly = true)
    public boolean isRoomOpen(Long roomId) {
        return roomRepository.findById(roomId).map(room -> room.getClosedAt() == null).orElse(false);
    }

    @Transactional(readOnly = true)
    public Long authenticatedUserId(Authentication authentication) {
        return authenticatedUser(authentication).getId();
    }

    private KaraokeRoomDetailResponse roomDetail(KaraokeRoom room, Long currentUserId) {
        List<KaraokeRoomMember> members = memberRepository
                .findAllByRoom_IdAndLeftAtIsNullOrderByJoinedAtAsc(room.getId());
        List<KaraokeRoomQueueItem> queueItems = queueRepository
                .findAllByRoom_IdOrderByCreatedAtAscIdAsc(room.getId());
        Map<String, Document> tracks = loadTrackDocuments(queueItems);

        List<KaraokeRoomMemberResponse> memberResponses = members.stream()
                .map(member -> new KaraokeRoomMemberResponse(
                        member.getUser().getId(),
                        member.getUser().getFullName(),
                        AudioUrlResolver.resolveImageUrl(member.getUser().getAvatarUrl()),
                        member.getUser().getId().equals(room.getHostUser().getId())))
                .toList();
        List<KaraokeRoomQueueItemResponse> queueResponses = queueItems.stream()
                .map(item -> toQueueItem(item, tracks.get(item.getTrackId()), currentUserId))
                .toList();

        User host = room.getHostUser();
        return new KaraokeRoomDetailResponse(room.getId(), room.getName(), host.getId(),
                host.getFullName(), AudioUrlResolver.resolveImageUrl(host.getAvatarUrl()),
                Math.min(room.getMaxMembers(), MAX_ROOM_CAPACITY), room.getVisibility(),
                host.getId().equals(currentUserId) ? room.getInviteCode() : null,
                room.getCreatedAt(), members.size(), host.getId().equals(currentUserId),
                memberResponses, queueResponses, playbackResponse(room));
    }

    private void ensureHostInviteCode(KaraokeRoom room, Long currentUserId) {
        if (room.getVisibility() != KaraokeRoomVisibility.PRIVATE
                || !room.getHostUser().getId().equals(currentUserId)
                || room.getInviteCode() != null) {
            return;
        }

        // Older rooms only stored the one-way hash, so rotate once to a new code
        // that the host can recover and share from inside the room.
        String inviteCode = createInviteCode();
        room.setInviteCode(inviteCode);
        room.setInviteCodeHash(hashInviteCode(inviteCode));
    }

    private Map<String, Document> loadTrackDocuments(List<KaraokeRoomQueueItem> queueItems) {
        List<ObjectId> ids = queueItems.stream()
                .map(KaraokeRoomQueueItem::getTrackId)
                .filter(ObjectId::isValid)
                .map(ObjectId::new)
                .distinct()
                .toList();
        if (ids.isEmpty()) return Map.of();
        List<Document> documents = mongo.find(Query.query(Criteria.where("_id").in(ids)), Document.class, TRACKS);
        Map<String, Document> byId = new LinkedHashMap<>();
        for (Document document : documents) {
            ObjectId id = document.getObjectId("_id");
            if (id != null) byId.put(id.toHexString(), document);
        }
        return byId;
    }

    private KaraokeRoomQueueItemResponse toQueueItem(KaraokeRoomQueueItem item, Document track, Long currentUserId) {
        String title = track == null ? "Bài hát không còn khả dụng" : valueOr(track.getString("name"), "Bài hát không tên");
        String artist = track == null ? "" : valueOr(track.getString("artist_name"), "");
        String imageUrl = track == null ? null : AudioUrlResolver.resolveImageUrl(track.getString("image_url"));
        Long durationMs = null;
        if (track != null && track.get("duration_ms") instanceof Number duration) durationMs = duration.longValue();
        return new KaraokeRoomQueueItemResponse(item.getId(), item.getTrackId(), title, artist, imageUrl,
                durationMs, item.getRequester().getFullName(), item.getRequester().getId().equals(currentUserId));
    }

    private KaraokeRoomSummaryResponse toSummary(KaraokeRoomSummaryProjection summary) {
        return new KaraokeRoomSummaryResponse(summary.getId(), summary.getName(), summary.getHostName(),
                AudioUrlResolver.resolveImageUrl(summary.getHostAvatarUrl()), summary.getMemberCount(),
                Math.min(summary.getMaxMembers(), MAX_ROOM_CAPACITY),
                KaraokeRoomVisibility.valueOf(summary.getVisibility()), summary.getCreatedAt());
    }

    private KaraokeRoomPlaybackResponse playbackResponse(KaraokeRoom room) {
        KaraokeRoomPlaybackTrackResponse trackResponse = null;
        if (room.getCurrentTrackId() != null && ObjectId.isValid(room.getCurrentTrackId())) {
            ObjectId trackId = new ObjectId(room.getCurrentTrackId());
            Document track = mongo.findById(trackId, Document.class, TRACKS);
            Document asset = mongo.findOne(Query.query(Criteria.where("songId").is(room.getCurrentTrackId())
                    .and("status").is("ready")), Document.class, ASSETS);
            if (track != null && asset != null) {
                Document assets = asset.get("assets", Document.class);
                Document instrumental = assets == null ? null : assets.get("instrumental", Document.class);
                Document storage = asset.get("storage", Document.class);
                String baseUrl = storage == null ? "https://moodify.click/" : storage.getString("baseUrl");
                Object rawDuration = track.get("duration_ms");
                Long duration = rawDuration instanceof Number number ? number.longValue() : null;
                trackResponse = new KaraokeRoomPlaybackTrackResponse(room.getCurrentTrackId(),
                        valueOr(track.getString("name"), "Bài hát không tên"), valueOr(track.getString("artist_name"), ""),
                        AudioUrlResolver.resolveImageUrl(track.getString("image_url")), duration,
                        instrumental == null ? null : urlFor(baseUrl, instrumental.getString("storageKey")),
                        track.getString("lyrics_plain"), track.getString("lyrics_synced"),
                        asset.get("lyricTimeline", Document.class));
            }
        }
        LocalDateTime serverNow = LocalDateTime.now();
        long serverTimeMs = System.currentTimeMillis();
        long position = room.getPlaybackPositionMs();
        if (room.getPlaybackStatus() == KaraokePlaybackStatus.PLAYING && room.getPlaybackStartedAt() != null) {
            position += Math.max(0, Duration.between(room.getPlaybackStartedAt(), serverNow).toMillis());
        }
        return new KaraokeRoomPlaybackResponse(room.getPlaybackStatus(), trackResponse, position,
                room.getPlaybackStartedAt(), room.getPlaybackVersion(), serverTimeMs, room.getPlaybackRunId());
    }

    private static String urlFor(String baseUrl, String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return null;
        String base = baseUrl == null || baseUrl.isBlank() ? "https://moodify.click/" : baseUrl;
        return base.replaceAll("/$", "") + "/" + storageKey.replaceAll("^/+", "");
    }

    private KaraokeRoom lockedRoom(Long roomId) {
        KaraokeRoom room = roomRepository.findByIdForUpdate(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND"));
        ensureOpen(room);
        return room;
    }

    private void requireHost(KaraokeRoom room, Long userId) {
        requireActiveMember(room, userId);
        if (!room.getHostUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROOM_HOST_REQUIRED");
        }
    }

    private static void requireVersion(KaraokeRoom room, long expectedVersion) {
        if (room.getPlaybackVersion() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLAYBACK_VERSION_CONFLICT");
        }
    }

    private void startNextTrack(KaraokeRoom room) {
        List<KaraokeRoomQueueItem> queued = queueRepository.findAllByRoom_IdOrderByCreatedAtAscIdAsc(room.getId());
        KaraokeRoomQueueItem next = queued.isEmpty() ? null : queued.getFirst();
        if (next == null) {
            room.setCurrentTrackId(null);
            room.setPlaybackRunId(null);
            room.setPlaybackStatus(KaraokePlaybackStatus.IDLE);
            room.setPlaybackPositionMs(0L);
            room.setPlaybackStartedAt(null);
        } else {
            room.setCurrentTrackId(next.getTrackId());
            room.setPlaybackRunId(UUID.randomUUID().toString());
            queueRepository.delete(next);
            room.setPlaybackPositionMs(0L);
            room.setPlaybackStartedAt(LocalDateTime.now());
            room.setPlaybackStatus(KaraokePlaybackStatus.PLAYING);
        }
        room.setPlaybackVersion(room.getPlaybackVersion() + 1);
    }

    private static void pause(KaraokeRoom room) {
        if (room.getPlaybackStatus() == KaraokePlaybackStatus.PLAYING && room.getPlaybackStartedAt() != null) {
            long elapsed = Math.max(0, Duration.between(room.getPlaybackStartedAt(), LocalDateTime.now()).toMillis());
            room.setPlaybackPositionMs(room.getPlaybackPositionMs() + elapsed);
        }
        if (room.getCurrentTrackId() != null) room.setPlaybackStatus(KaraokePlaybackStatus.PAUSED);
        else room.setPlaybackStatus(KaraokePlaybackStatus.IDLE);
        room.setPlaybackStartedAt(null);
    }

    private void transferHostOrPause(KaraokeRoom room, Long leavingHostId) {
        List<KaraokeRoomMember> remaining = memberRepository
                .findAllByRoom_IdAndLeftAtIsNullOrderByJoinedAtAsc(room.getId());
        if (room.getHostUser().getId().equals(leavingHostId)) {
            pause(room);
            room.setPlaybackVersion(room.getPlaybackVersion() + 1);
            remaining.stream()
                    .filter(member -> realtimeGateway.hasRoomMemberSession(room.getId(), member.getUser().getId()))
                    .findFirst()
                    .ifPresent(member -> room.setHostUser(member.getUser()));
        } else if (remaining.isEmpty()) {
            pause(room);
            room.setPlaybackVersion(room.getPlaybackVersion() + 1);
        }
    }

    private void recoverHostIfMissing(KaraokeRoom room, User joiningUser) {
        KaraokeRoomMember hostMembership = memberRepository
                .findByRoom_IdAndUser_Id(room.getId(), room.getHostUser().getId())
                .orElse(null);
        if (hostMembership != null && hostMembership.getLeftAt() == null) return;
        if (room.getPlaybackStatus() == KaraokePlaybackStatus.PLAYING) pause(room);
        room.setHostUser(joiningUser);
        room.setPlaybackVersion(room.getPlaybackVersion() + 1);
    }

    private Document findUsableKaraokeTrack(String trackId) {
        if (!ObjectId.isValid(trackId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRACK_NOT_AVAILABLE");
        }
        Document asset = mongo.findOne(Query.query(Criteria.where("songId").is(trackId)
                .and("status").is("ready")), Document.class, ASSETS);
        Document files = asset == null ? null : asset.get("assets", Document.class);
        if (!hasStorageKey(files, "instrumental") || !hasStorageKey(files, "vocals")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRACK_NOT_AVAILABLE");
        }
        Document track = mongo.findById(new ObjectId(trackId), Document.class, TRACKS);
        if (track == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TRACK_NOT_AVAILABLE");
        return track;
    }

    private static boolean hasStorageKey(Document assets, String role) {
        if (assets == null) return false;
        Document item = assets.get(role, Document.class);
        String storageKey = item == null ? null : item.getString("storageKey");
        return storageKey != null && !storageKey.isBlank();
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED");
        }
        User user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "USER_NOT_ACTIVE");
        }
        return user;
    }

    private void requireActiveMember(KaraokeRoom room, Long userId) {
        boolean active = memberRepository.findByRoom_IdAndUser_Id(room.getId(), userId)
                .map(member -> member.getLeftAt() == null)
                .orElse(false);
        if (!active) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROOM_MEMBERSHIP_REQUIRED");
    }

    private void ensureOpen(KaraokeRoom room) {
        if (room.getClosedAt() != null) throw new ResponseStatusException(HttpStatus.GONE, "ROOM_CLOSED");
    }

    private void verifyInviteCode(KaraokeRoom room, String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROOM_INVITE_REQUIRED");
        }
        String expected = room.getInviteCodeHash();
        String actual = hashInviteCode(inviteCode.trim());
        if (expected == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROOM_INVITE_INVALID");
        }
    }

    private static String createInviteCode() {
        byte[] bytes = new byte[18];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashInviteCode(String inviteCode) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(inviteCode.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
