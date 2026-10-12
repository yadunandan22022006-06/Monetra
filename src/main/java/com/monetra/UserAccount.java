package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** A registered Monetra user. The password is stored only as a BCrypt hash. */
@Entity
@Table(name = "app_users", indexes = @Index(name = "idx_users_phone", columnList = "phoneKey"))
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(nullable = false)
    private String passwordHash;

    @Column(length = 32)
    private String phone;

    /** Last 10 digits of the phone number, used to find a user from a number or QR code. */
    @Column(length = 10)
    private String phoneKey;

    @Column(length = 60)
    private String fullName;

    /** The PayPal email money is paid out to when someone pays this user. */
    @Column(length = 120)
    private String paypalEmail;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected UserAccount() {
    }

    public String getPaypalEmail() {
        return paypalEmail;
    }

    public void setPaypalEmail(String paypalEmail) {
        this.paypalEmail = paypalEmail == null || paypalEmail.isBlank() ? null : paypalEmail.trim().toLowerCase();
    }

    public UserAccount(String email, String passwordHash, String phone) {
        this(email, passwordHash, phone, null);
    }

    public UserAccount(String email, String passwordHash, String phone, String fullName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.phone = phone;
        this.phoneKey = Phones.key(phone);
        this.fullName = fullName == null || fullName.isBlank() ? null : fullName.trim();
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getPhone() {
        return phone;
    }

    public String getPhoneKey() {
        return phoneKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** The name other people see: the full name, or a tidy version of the email. */
    public String displayName() {
        if (fullName != null && !fullName.isBlank()) {
            return fullName;
        }

        String local = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;

        String cleaned = local.replaceAll("[._\\-+0-9]+", " ").trim();

        if (cleaned.isEmpty()) {
            return local;
        }

        StringBuilder out = new StringBuilder();

        for (String word : cleaned.split("\\s+")) {
            if (out.length() > 0) {
                out.append(' ');
            }

            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }

        return out.toString();
    }
}
