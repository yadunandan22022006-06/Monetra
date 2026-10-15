package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface GuardianScoreRecordRepository extends JpaRepository<GuardianScoreRecord, Long> {

    Optional<GuardianScoreRecord> findByUserIdAndDay(Long userId, LocalDate day);

    List<GuardianScoreRecord> findTop30ByUserIdOrderByDayDesc(Long userId);
}
