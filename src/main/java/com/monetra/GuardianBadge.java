package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** A Guardian badge one user earned: when, and with which score. Each badge is earned once. */
@Entity
@Table(name = "guardian_badges",
        uniqueConstraints = @UniqueConstraint(name = "uq_guardian_badge", columnNames = {"userId", "level"}))
public class GuardianBadge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    /** BEGINNER, BUILDER, PROTECTOR, CHAMPION or ELITE. */
    @Column(nullable = false, length = 12)
    public String level;

    @Column(nullable = false)
    public int scoreWhenEarned;

    @Column(nullable = false)
    public Instant earnedAt = Instant.now();
}
