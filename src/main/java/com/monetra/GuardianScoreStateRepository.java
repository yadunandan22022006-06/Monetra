package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GuardianScoreStateRepository extends JpaRepository<GuardianScoreState, Long> {

    Optional<GuardianScoreState> findByUserId(Long userId);
}
