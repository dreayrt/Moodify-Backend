package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.KaraokeRoom;
import com.laphuth.moodify.entities.KaraokePlaybackStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KaraokeRoomRepository extends JpaRepository<KaraokeRoom, Long> {
    @Query(value = """
            SELECT r.id AS id,
                   r.name AS name,
                   h.full_name AS hostName,
                   h.avatar_url AS hostAvatarUrl,
                   r.max_members AS maxMembers,
                   r.visibility AS visibility,
                   r.created_at AS createdAt,
                   COUNT(m.user_id) AS memberCount
            FROM karaoke_rooms r
            JOIN users h ON h.id = r.host_user_id
            LEFT JOIN karaoke_room_members m
                   ON m.room_id = r.id AND m.left_at IS NULL
            WHERE r.closed_at IS NULL
            GROUP BY r.id, r.name, h.full_name, h.avatar_url,
                     r.max_members, r.visibility, r.created_at
            ORDER BY r.created_at DESC, r.id DESC
            """, nativeQuery = true)
    List<KaraokeRoomSummaryProjection> findOpenRoomSummaries();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from KaraokeRoom r where r.id = :roomId")
    Optional<KaraokeRoom> findByIdForUpdate(@Param("roomId") Long roomId);

    @Query("select room.id from KaraokeRoom room where room.playbackStatus = :playbackStatus and room.closedAt is null")
    List<Long> findAllIdsByPlaybackStatus(@Param("playbackStatus") KaraokePlaybackStatus playbackStatus);

    @Query(value = """
            SELECT r.id
            FROM karaoke_rooms r
            WHERE r.closed_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM karaoke_room_members active_member
                  WHERE active_member.room_id = r.id AND active_member.left_at IS NULL
              )
              AND COALESCE(
                  (SELECT MAX(member.left_at) FROM karaoke_room_members member WHERE member.room_id = r.id),
                  r.created_at
              ) <= :cutoff
            """, nativeQuery = true)
    List<Long> findEmptyOpenRoomIdsBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("update KaraokeRoom room set room.closedAt = :closedAt where room.id in :roomIds and room.closedAt is null")
    int closeRooms(@Param("roomIds") List<Long> roomIds, @Param("closedAt") LocalDateTime closedAt);
}
