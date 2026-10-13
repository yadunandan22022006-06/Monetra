package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * Remembers which Guardian notifications a user was already sent, so each one is sent once.
 * The key says what it was about, for example "EMI_TODAY:1790000000000:2026-11-05".
 */
@Entity
@Table(name = "guardian_alerts",
        uniqueConstraints = @UniqueConstraint(name = "uq_guardian_alert", columnNames = {"userId", "alertKey"}))
public class GuardianAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    @Column(nullable = false, length = 80)
    public String alertKey;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
