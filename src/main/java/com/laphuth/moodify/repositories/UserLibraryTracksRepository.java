package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.User;
import com.laphuth.moodify.entities.UserLibraryTracks;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserLibraryTracksRepository extends JpaRepository<UserLibraryTracks, String> {
    boolean existsByUserAndTrackSpotifyId(User user, String trackSpotifyId);
    
    void deleteByUserAndTrackSpotifyId(User user, String trackSpotifyId);
    
    Page<UserLibraryTracks> findByUserOrderByAddedAtDesc(User user, Pageable pageable);

    @Query("SELECT ult.trackSpotifyId FROM UserLibraryTracks ult WHERE ult.user = :user")
    List<String> findTrackSpotifyIdsByUser(@Param("user") User user);
}
