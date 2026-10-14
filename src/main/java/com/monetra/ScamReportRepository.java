package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScamReportRepository extends JpaRepository<ScamReport, Long> {

    List<ScamReport> findByUserId(Long userId);
}
