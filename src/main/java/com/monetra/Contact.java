package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** A person the user saved to pay again quickly, or brought in from their phone's contact list. */
@Entity
@Table(name = "contacts",
        uniqueConstraints = @UniqueConstraint(name = "uq_contact", columnNames = {"userId", "phoneKey"}))
public class Contact {

    /** The source of a contact that came from the phone's contact list. */
    public static final String PHONE = "PHONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false)
    public Long userId;

    @Column(nullable = false, length = 60)
    public String name;

    @Column(nullable = false, length = 10)
    public String phoneKey;

    /** "PHONE" for a contact from the phone's contact list. Empty for one saved by hand. */
    @Column(length = 10)
    public String source;

    @Column(nullable = false)
    public Instant createdAt = Instant.now();
}
