package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GuardianAlertRepository extends JpaRepository<GuardianAlert, Long> {

    boolean existsByUserIdAndAlertKey(Long userId, String alertKey);
}
