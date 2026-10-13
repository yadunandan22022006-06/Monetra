package com.monetra;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-app notifications, for example "You received ₹500 from Priya". */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository notifications;
    private final HomeExpenseService home;
    private final GuardianAnalysisService guardian;

    public NotificationController(
            NotificationRepository notifications,
            HomeExpenseService home,
            GuardianAnalysisService guardian) {

        this.notifications = notifications;
        this.home = home;
        this.guardian = guardian;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in first."));
        }

        /* Household reminders are created the first time they are due, then shown like any notification. */
        try {
            home.createReminders(userId, java.time.LocalDate.now());

        } catch (RuntimeException e) {
            /* Reminders are a bonus: never break the notification list because of them. */
        }

        /* The same goes for the Guardian's own alerts: daily limit, EMIs, savings goals and HE reminders. */
        try {
            guardian.createNotifications(userId, java.time.LocalDate.now(), java.time.LocalTime.now());

        } catch (RuntimeException e) {
            /* Never break the notification list because of them. */
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unread", notifications.countByUserIdAndSeenFalse(userId));
        out.put("items", notifications.findTop30ByUserIdOrderByCreatedAtDesc(userId));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/read")
    public ResponseEntity<?> markRead(HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in first."));
        }

        List<Notification> unread = notifications.findByUserIdAndSeenFalse(userId);

        for (Notification n : unread) {
            n.seen = true;
        }

        notifications.saveAll(unread);

        return ResponseEntity.ok(Map.of("marked", unread.size()));
    }
}
