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
 * One Account Aggregator consent: the user's permission, given on Setu's screens, for Monetra to read
 * their bank account's summary. Monetra never sees the user's bank login, PIN or OTP.
 */
@Entity
@Table(name = "bank_links", uniqueConstraints = @UniqueConstraint(name = "uq_bank_link_consent", columnNames = "consentId"))
public class BankLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    /** Setu's consent id. */
    @Column(nullable = false, length = 64)
    public String consentId;

    /** PENDING, ACTIVE, REJECTED, REVOKED, PAUSED or EXPIRED, as Setu last reported it. */
    @Column(nullable = false, length = 16)
    public String status = "PENDING";

    /** The data range the consent covers; every data request has to stay inside it. */
    @Column(nullable = false)
    public Instant rangeFrom;

    @Column(nullable = false)
    public Instant rangeTo;

    /** A data request that was still being prepared, so the next check reads it instead of asking again. */
    @Column(length = 64)
    public String pendingSessionId;

    /** True when this came from Setu's sandbox: test accounts, not a real bank. */
    @Column(nullable = false)
    public boolean sandbox = true;

    @Column(length = 200)
    public String lastError;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
