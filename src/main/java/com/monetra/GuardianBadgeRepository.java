package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GuardianBadgeRepository extends JpaRepository<GuardianBadge, Long> {

    List<GuardianBadge> findByUserIdOrderByEarnedAtAsc(Long userId);
}
