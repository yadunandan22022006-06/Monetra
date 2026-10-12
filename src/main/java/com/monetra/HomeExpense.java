package com.monetra;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A household expense or contribution the user wants to remember (rent, Wi-Fi, money for home...). */
@Entity
@Table(name = "home_expenses", indexes = @Index(name = "idx_home_user", columnList = "userId"))
public class HomeExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @JsonIgnore
    @Column(nullable = false)
    public Long userId;

    @Column(nullable = false, length = 60)
    public String name;

    @Column(nullable = false, precision = 14, scale = 2)
    public BigDecimal amount;

    @Column(length = 30)
    public String category;

    /** ONCE, DAILY, WEEKLY, MONTHLY or YEARLY. */
    @Column(nullable = false, length = 8)
    public String frequency;

    /** The first due date. Later due dates follow the frequency. */
    @Column(nullable = false)
    public LocalDate startDate;

    /** Mobile number (last 10 digits) of the person this is paid to, if any. */
    @Column(length = 10)
    public String recipientPhone;

    @Column(length = 100)
    public String note;

    public int remindDaysBefore;

    public boolean active = true;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
