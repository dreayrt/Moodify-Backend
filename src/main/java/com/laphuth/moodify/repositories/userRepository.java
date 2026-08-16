package com.laphuth.moodify.repositories;

import com.laphuth.moodify.entities.user;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface userRepository extends JpaRepository<user, Long> {
    Optional<user> findByEmail(String email);

    Optional<user> findByUsername(String username);

    Optional<user> findByEmailOrUsername(String email, String username);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);
}
