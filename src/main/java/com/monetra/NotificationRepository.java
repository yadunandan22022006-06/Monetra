package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop30ByUserIdOrderByCreatedAtDesc(Long userId);

    /** One side of the notification screen: the Guardian Score and badge kinds, or everything else. */
    List<Notification> findTop30ByUserIdAndTypeInOrderByCreatedAtDesc(Long userId, Collection<String> types);

    List<Notification> findTop30ByUserIdAndTypeNotInOrderByCreatedAtDesc(Long userId, Collection<String> types);

    List<Notification> findByUserIdAndSeenFalse(Long userId);

    long countByUserIdAndSeenFalse(Long userId);
}
