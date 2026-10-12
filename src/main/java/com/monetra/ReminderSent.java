package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

/** Remembers which reminders were already sent, so each one is sent once. */
@Entity
@Table(name = "reminders_sent",
        uniqueConstraints = @UniqueConstraint(name = "uq_reminder", columnNames = {"expenseId", "dueDate", "kind"}))
public class ReminderSent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long expenseId;

    @Column(nullable = false)
    public LocalDate dueDate;

    /** UPCOMING, DUE or OVERDUE. */
    @Column(nullable = false, length = 8)
    public String kind;
}
