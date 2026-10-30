package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.KaraokeRoomQueueItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface KaraokeRoomQueueRepository extends JpaRepository<KaraokeRoomQueueItem, Long> {
    List<KaraokeRoomQueueItem> findAllByRoom_IdOrderByCreatedAtAscIdAsc(Long roomId);
    Optional<KaraokeRoomQueueItem> findByIdAndRoom_Id(Long id, Long roomId);
    boolean existsByRoom_IdAndRequester_Id(Long roomId, Long requesterId);

    @Modifying
    @Query("delete from KaraokeRoomQueueItem item where item.room.id in :roomIds")
    int deleteAllForRooms(@Param("roomIds") List<Long> roomIds);
}
