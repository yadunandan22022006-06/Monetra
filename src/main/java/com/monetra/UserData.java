package com.monetra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One user's dashboard records (income, expenses, loans, savings, goals, limits)
 * stored as a JSON document, so the dashboard keeps its current shape.
 */
@Entity
@Table(name = "user_data")
public class UserData {

    @Id
    private Long userId;

    @Lob
    @Column(nullable = false)
    private String json;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    protected UserData() {
    }

    public UserData(Long userId, String json) {
        this.userId = userId;
        this.json = json;
    }

    public Long getUserId() {
        return userId;
    }

    public String getJson() {
        return json;
    }

    public void setJson(String json) {
        this.json = json;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
