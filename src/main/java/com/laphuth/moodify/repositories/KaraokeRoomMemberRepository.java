package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.KaraokeRoomMember;
import com.laphuth.moodify.entities.KaraokeRoomMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface KaraokeRoomMemberRepository extends JpaRepository<KaraokeRoomMember, KaraokeRoomMemberId> {
    Optional<KaraokeRoomMember> findByRoom_IdAndUser_Id(Long roomId, Long userId);
    @Query("select count(member) from KaraokeRoomMember member where member.room.id = :roomId and member.user.id = :userId and member.leftAt is null and member.room.closedAt is null")
    long countActiveMembersInOpenRoom(@Param("roomId") Long roomId, @Param("userId") Long userId);
    List<KaraokeRoomMember> findAllByRoom_IdAndLeftAtIsNullOrderByJoinedAtAsc(Long roomId);
    long countByRoom_IdAndLeftAtIsNull(Long roomId);

    @Query("select member from KaraokeRoomMember member where member.leftAt is null")
    List<KaraokeRoomMember> findAllActiveMembers();

    @Query("select member from KaraokeRoomMember member where member.leftAt is null and member.joinedAt < :cutoff")
    @EntityGraph(attributePaths = {"room", "user"})
    List<KaraokeRoomMember> findActiveMembersJoinedBefore(@Param("cutoff") LocalDateTime cutoff);

    @Modifying
    @Query("update KaraokeRoomMember member set member.leftAt = :leftAt where member.leftAt is null")
    int markAllActiveAsLeft(@Param("leftAt") LocalDateTime leftAt);
}
