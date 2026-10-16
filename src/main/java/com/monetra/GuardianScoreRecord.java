package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;

/** One user's Guardian Score for one day: the latest one worked out that day, never more than one row. */
@Entity
@Table(name = "guardian_score_history",
        uniqueConstraints = @UniqueConstraint(name = "uq_score_history_day", columnNames = {"userId", "score_day"}))
public class GuardianScoreRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    /** "day" is a reserved word in the database, so the column has its own name. */
    @Column(name = "score_day", nullable = false)
    public LocalDate day;

    @Column(nullable = false)
    public int score;

    @Column(length = 20)
    public String confidence;

    @Column(length = 8)
    public String rulesVersion;

    @Column(nullable = false)
    public Instant updatedAt = Instant.now();
}
