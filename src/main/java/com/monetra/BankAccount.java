package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A bank account linked through an Account Aggregator consent, with the last balance the bank
 * returned. Only the masked account number is kept; the full number never reaches Monetra.
 */
@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    @Column(nullable = false, length = 64)
    public String consentId;

    /** The bank (FIP) the account is with, as the Account Aggregator names it. */
    @Column(length = 80)
    public String fipId;

    /** The Account Aggregator's reference for this account. */
    @Column(nullable = false, length = 80)
    public String linkRefNumber;

    @Column(length = 40)
    public String maskedAccNumber;

    /** SAVINGS, CURRENT and so on, when the bank says. */
    @Column(length = 20)
    public String accountType;

    @Column(nullable = false)
    public boolean primaryAccount;

    /** The last balance the bank returned, and when the bank said it was correct. Null until one arrives. */
    @Column(precision = 19, scale = 2)
    public BigDecimal balance;

    @Column(length = 8)
    public String currency;

    @Column(length = 40)
    public String balanceDateTime;

    /** When Monetra last received a balance for this account. */
    public Instant lastCheckedAt;

    /** True when the balance came from Setu's sandbox (test data), never from a real bank. */
    @Column(nullable = false)
    public boolean sandbox = true;
}
