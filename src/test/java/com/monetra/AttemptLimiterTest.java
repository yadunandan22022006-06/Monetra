package com.monetra;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttemptLimiterTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-16T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void blocksAfterTheLastAllowedTryAndLetsAnotherKeyThrough() {
        AttemptLimiter limiter = new AttemptLimiter(3, Duration.ofMinutes(15), new TestClock());

        for (int i = 0; i < 3; i++) {
            assertFalse(limiter.blocked("a"), "try " + (i + 1) + " is still allowed");
            limiter.record("a");
        }

        assertTrue(limiter.blocked("a"));
        assertFalse(limiter.blocked("b"), "another key is not affected");
    }

    @Test
    void theBlockEndsWhenTheWindowHasPassed() {
        TestClock clock = new TestClock();
        AttemptLimiter limiter = new AttemptLimiter(2, Duration.ofMinutes(15), clock);

        limiter.record("a");
        limiter.record("a");

        assertTrue(limiter.blocked("a"));

        clock.advance(Duration.ofMinutes(14));
        assertTrue(limiter.blocked("a"), "still inside the window");

        clock.advance(Duration.ofMinutes(2));
        assertFalse(limiter.blocked("a"), "the window has passed");
    }

    @Test
    void clearingForgetsTheTries() {
        AttemptLimiter limiter = new AttemptLimiter(2, Duration.ofMinutes(15), new TestClock());

        limiter.record("a");
        limiter.record("a");
        limiter.clear("a");

        assertFalse(limiter.blocked("a"));
    }

    @Test
    void aHugeNumberOfDifferentKeysDoesNotGrowTheMemoryWithoutEnd() {
        TestClock clock = new TestClock();
        AttemptLimiter limiter = new AttemptLimiter(2, Duration.ofMinutes(1), clock);

        for (int i = 0; i < 10_000; i++) {
            limiter.record("old-" + i);
        }

        clock.advance(Duration.ofMinutes(5));

        /* The next try finds the table full, drops everything older than the window, and still counts. */
        limiter.record("new");
        limiter.record("new");

        assertTrue(limiter.blocked("new"));
        assertFalse(limiter.blocked("old-1"));
    }
}
