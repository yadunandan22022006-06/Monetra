package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A home expense (HE) the user paid outside PayPal, in cash or by a bank transfer they made
 * themselves, and recorded in Monetra. No money is moved for these: Monetra only remembers them.
 * Home expenses paid through PayPal are ordinary payments marked with their HE type instead.
 */
@Entity
@Table(name = "he_payments",
        indexes = @Index(name = "idx_he_user", columnList = "userId"),
        uniqueConstraints = @UniqueConstraint(name = "uq_he_request", columnNames = {"userId", "requestId"}))
public class HePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    /** DAILY, MONTHLY or YEARLY. */
    @Column(nullable = false, length = 8)
    public String type;

    @Column(nullable = false, precision = 14, scale = 2)
    public BigDecimal amount;

    @Column(length = 100)
    public String description;

    /** CASH or BANK. */
    @Column(nullable = false, length = 8)
    public String method;

    /** Who was paid, as the user typed it. */
    @Column(length = 60)
    public String recipient;

    /** For a bank transfer: how it was sent and where, for example "IMPS • HDFC Bank • ••••1234". Never a full account number. */
    @Column(length = 120)
    public String detail;

    /** For a bank transfer: the bank's own reference (UTR), if the user has it. */
    @Column(length = 40)
    public String reference;

    /** Sent by the page once per press of the button, so a double press is recorded once. */
    @Column(length = 40)
    public String requestId;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
