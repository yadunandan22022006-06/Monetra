package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface GuardianPeriodBadgeRepository extends JpaRepository<GuardianPeriodBadge, Long> {

    List<GuardianPeriodBadge> findByUserIdAndKindAndStartGreaterThanEqualOrderByStartAsc(Long userId, String kind, LocalDate from);

    List<GuardianPeriodBadge> findTop8ByUserIdAndKindOrderByStartDesc(Long userId, String kind);

    long countByUserIdAndKindAndEarnedTrue(Long userId, String kind);
}
