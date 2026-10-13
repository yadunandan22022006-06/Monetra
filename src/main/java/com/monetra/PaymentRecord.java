package com.monetra;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** One payment attempt. Public fields are returned to the browser as JSON. */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_user", columnList = "userId"),
        @Index(name = "idx_payments_recipient", columnList = "recipientUserId"),
        @Index(name = "idx_payments_order", columnList = "orderId")
})
public class PaymentRecord {

    @Id
    @Column(length = 40)
    public String paymentId;

    /** The sender. */
    @JsonIgnore
    @Column(nullable = false)
    public Long userId;

    /** The receiver, when the number or QR belongs to a Monetra user. */
    @JsonIgnore
    public Long recipientUserId;

    /** Stable id of the receiver used by the fraud rules, e.g. P:9876543210 or B:HDFC0001234:5678. */
    @JsonIgnore
    @Column(length = 40)
    public String recipientKey;

    /** PHONE, QR or BANK. */
    @Column(length = 8)
    public String method;

    @Column(length = 64)
    public String orderId;

    @Column(length = 64)
    public String captureId;

    @Column(length = 24)
    public String status;

    @Column(length = 200)
    public String message;

    @Column(length = 60)
    public String recipientName;

    @Column(length = 16)
    public String recipientPhone;

    @Column(length = 60)
    public String bankName;

    @Column(length = 11)
    public String ifsc;

    @Column(length = 4)
    public String bankLast4;

    @Column(length = 100)
    public String description;

    /** Reasons the Fraud Guard asked for confirmation, joined with " | ". */
    @Column(length = 400)
    public String riskFlags;

    /** NONE, WAITING_EMAIL, PENDING, SENT or FAILED: what happened to the payout to the receiver's PayPal. */
    @Column(length = 16)
    public String payoutStatus;

    @JsonIgnore
    @Column(length = 40)
    public String payoutBatchId;

    @Column(length = 200)
    public String payoutMessage;

    /** If this payment settles a household expense: which one and which due date. */
    @JsonIgnore
    public Long homeExpenseId;

    @JsonIgnore
    @Column(length = 10)
    public String homeDueDate;

    /**
     * DAILY, MONTHLY or YEARLY when this is a home expense (HE) payment. HE payments have their own
     * history and are left out of the ordinary payments list. Empty for an ordinary payment.
     */
    @Column(length = 8)
    public String heType;

    /** How an HE payment was made: SCAN or MOBILE. Both are paid through PayPal. */
    @Column(length = 8)
    public String heMethod;

    @Column(precision = 14, scale = 2)
    public BigDecimal amountInr;

    @Column(precision = 14, scale = 2)
    public BigDecimal amountUsd;

    @Column(length = 40)
    public String createdAt;
}
