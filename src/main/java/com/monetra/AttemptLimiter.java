package com.monetra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows a limited number of tries in a time window, for example 8 wrong passwords in 15 minutes.
 * Used for sign-in and for asking for sign-up codes, so neither can be tried over and over.
 * It lives in memory: a restart gives everyone a fresh start, which is fine for this purpose.
 */
public class AttemptLimiter {

    private static final int MAX_KEYS = 10_000;

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> tries = new ConcurrentHashMap<>();

    public AttemptLimiter(int max, Duration window) {
        this(max, window, Clock.systemUTC());
    }

    AttemptLimiter(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    /** True when this key has already used all its tries inside the window. */
    public boolean blocked(String key) {
        Deque<Instant> recent = tries.get(key);

        if (recent == null) {
            return false;
        }

        synchronized (recent) {
            forget(recent);

            return recent.size() >= max;
        }
    }

    /** Counts one more try for this key. */
    public void record(String key) {
        if (tries.size() >= MAX_KEYS) {
            tidy();
        }

        Deque<Instant> recent = tries.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (recent) {
            forget(recent);

            recent.addLast(clock.instant());
        }
    }

    /** Forgets this key's tries, for example after a correct password. */
    public void clear(String key) {
        tries.remove(key);
    }

    private void forget(Deque<Instant> recent) {
        Instant cutoff = clock.instant().minus(window);

        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.pollFirst();
        }
    }

    /** Drops every key whose tries are all older than the window, so the map cannot grow without end. */
    private void tidy() {
        tries.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                forget(entry.getValue());

                return entry.getValue().isEmpty();
            }
        });
    }
}
