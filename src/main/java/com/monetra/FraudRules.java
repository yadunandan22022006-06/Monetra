package com.monetra;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Fraud Guard: simple, explainable checks run before a payment is sent.
 * A flag never blocks a payment by itself. It asks the user to confirm.
 */
public final class FraudRules {

    /** A payment the user made before. */
    public record Past(String recipientKey, BigDecimal amount, Instant at, String status) {
    }

    static final BigDecimal LARGE_FIRST_PAYMENT = new BigDecimal("5000");
    static final BigDecimal UNUSUAL_MULTIPLE = new BigDecimal("3");
    static final int MIN_HISTORY = 3;
    static final int RAPID_COUNT = 3;
    static final Duration RAPID_WINDOW = Duration.ofMinutes(10);

    private FraudRules() {
    }

    public static List<String> assess(List<Past> past, String recipientKey, BigDecimal amount, Instant now) {
        List<String> reasons = new ArrayList<>();

        boolean knownRecipient = false;
        BigDecimal total = BigDecimal.ZERO;
        int completed = 0;
        int recent = 0;

        for (Past p : past) {
            if ("COMPLETED".equals(p.status())) {
                completed++;
                total = total.add(p.amount());

                if (p.recipientKey() != null && p.recipientKey().equals(recipientKey)) {
                    knownRecipient = true;
                }
            }

            boolean counts = !"CANCELLED".equals(p.status());

            if (counts && p.at() != null && Duration.between(p.at(), now).compareTo(RAPID_WINDOW) <= 0) {
                recent++;
            }
        }

        if (!knownRecipient && amount.compareTo(LARGE_FIRST_PAYMENT) >= 0) {
            reasons.add("This is your first payment to this recipient and the amount is large.");
        }

        if (completed >= MIN_HISTORY) {
            BigDecimal average = total.divide(BigDecimal.valueOf(completed), 2, java.math.RoundingMode.HALF_UP);

            if (amount.compareTo(average.multiply(UNUSUAL_MULTIPLE)) > 0) {
                reasons.add("This amount is more than 3 times your usual payment of about "
                        + Inr.whole(average) + ".");
            }
        }

        if (recent >= RAPID_COUNT) {
            reasons.add("You have started " + recent + " payments in the last 10 minutes.");
        }

        return reasons;
    }
}
