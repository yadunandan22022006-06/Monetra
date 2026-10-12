package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop30ByUserIdOrderByCreatedAtDesc(Long userId);

    List<Notification> findByUserIdAndSeenFalse(Long userId);

    long countByUserIdAndSeenFalse(Long userId);
}
