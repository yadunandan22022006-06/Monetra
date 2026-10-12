package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** An in-app notification, for example "You received ₹500 from Priya". */
@Entity
@Table(name = "notifications", indexes = @Index(name = "idx_notifications_user", columnList = "userId"))
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(nullable = false)
    public Long userId;

    /** PAYMENT_RECEIVED, PAYMENT_SENT, PAYMENT_FAILED or ALERT. */
    @Column(length = 20)
    public String type;

    @Column(length = 300)
    public String message;

    @Column(length = 40)
    public String paymentId;

    public boolean seen;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();

    protected Notification() {
    }

    public Notification(Long userId, String type, String message, String paymentId) {
        this.userId = userId;
        this.type = type;
        this.message = message;
        this.paymentId = paymentId;
    }
}
