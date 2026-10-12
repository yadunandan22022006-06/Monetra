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

/** One due date of a household expense that has been paid. */
@Entity
@Table(name = "home_expense_payments",
        uniqueConstraints = @UniqueConstraint(name = "uq_home_paid", columnNames = {"expenseId", "dueDate"}))
public class HomeExpensePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long expenseId;

    @Column(nullable = false)
    public LocalDate dueDate;

    @Column(nullable = false, precision = 14, scale = 2)
    public BigDecimal amount;

    /** MANUAL (the user says they paid) or PAYPAL (confirmed through Monetra and PayPal). */
    @Column(nullable = false, length = 8)
    public String source;

    @Column(length = 40)
    public String paymentId;

    @Column(nullable = false)
    public Instant paidAt = Instant.now();
}
