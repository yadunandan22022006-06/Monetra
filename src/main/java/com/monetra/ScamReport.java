package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A user telling Monetra that a Scam Shield result was wrong. Only the result and the rules that
 * matched are kept. The message itself is kept only when the user ticked that they agree to share it.
 */
@Entity
@Table(name = "scam_reports")
public class ScamReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    /** What Scam Shield said: LOW, MEDIUM or HIGH. */
    @Column(nullable = false, length = 8)
    public String shieldLevel;

    /** What the user says it really is: SCAM or SAFE. */
    @Column(nullable = false, length = 8)
    public String userSays;

    /** The rules that matched, for example "ELECTRICITY,LINK". */
    @Column(length = 200)
    public String rules;

    /** Null unless the user agreed to share the message. */
    @Column(length = ScamShieldService.MAX_LENGTH)
    public String message;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
