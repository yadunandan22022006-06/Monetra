package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One finished day or week of one user, and whether it earned its badge: a Good Day badge for a day
 * within the daily limit, a Good Week badge for a week with enough Good Days. Each day and each week
 * is decided once, when it is over, and kept, earned or not.
 */
@Entity
@Table(name = "guardian_period_badges",
        uniqueConstraints = @UniqueConstraint(name = "uq_period_badge", columnNames = {"userId", "kind", "period_start"}))
public class GuardianPeriodBadge {

    public static final String DAY = "DAY";
    public static final String WEEK = "WEEK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    /** DAY or WEEK. */
    @Column(nullable = false, length = 4)
    public String kind;

    /** The day itself, or the Monday that starts the week. */
    @Column(name = "period_start", nullable = false)
    public LocalDate start;

    public boolean earned;

    /** A day: what was spent, and the daily limit it was checked against. */
    @Column(precision = 14, scale = 2)
    public BigDecimal spent;

    @Column(name = "limit_amount", precision = 14, scale = 2)
    public BigDecimal limitAmount;

    /** A week: how many of its days were Good Days, out of how many days that were decided. */
    public Integer goodDays;

    public Integer days;

    @Column(nullable = false)
    public Instant decidedAt = Instant.now();
}
