package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Where one user stands with the Guardian Score: the level they are at now, the highest level they
 * ever reached, their last score, their time zone and the day they last got the daily update.
 */
@Entity
@Table(name = "guardian_score_state",
        uniqueConstraints = @UniqueConstraint(name = "uq_score_state_user", columnNames = "userId"))
public class GuardianScoreState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    /** BEGINNER, BUILDER, PROTECTOR, CHAMPION or ELITE; empty while no score can be worked out. */
    @Column(length = 12)
    public String currentLevel;

    /** The highest level ever reached. It stays when the score goes down. */
    @Column(length = 12)
    public String highestLevel;

    public Integer lastScore;

    @Column(length = 20)
    public String lastConfidence;

    public Instant lastCalculatedAt;

    /** The user's own time zone, for example "Asia/Kolkata", as their browser reported it. */
    @Column(length = 40)
    public String timezone;

    /** The local day the last daily Guardian update was created for. */
    public LocalDate lastDailyDate;

    /** The tip in the last daily update, so the same one is not repeated day after day. */
    @Column(length = 200)
    public String lastDailyTip;

    @Version
    public Long version;
}
