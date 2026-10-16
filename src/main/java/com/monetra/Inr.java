package com.monetra;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** How the Guardian writes money and dates in its messages: "₹33,232.82" and "5 Nov 2026". */
public final class Inr {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private Inr() {
    }

    /** "₹1,50,000.00". A negative amount is written "-₹1,000.00". */
    public static String of(BigDecimal amount) {
        BigDecimal value = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);

        /* Java's number formats only know one group size, so the Indian grouping (3, then 2, 2...) is done by hand. */
        String[] parts = value.abs().toPlainString().split("\\.");
        String whole = parts[0];

        StringBuilder grouped = new StringBuilder(whole.length() <= 3 ? whole : whole.substring(whole.length() - 3));

        for (int end = whole.length() - 3; end > 0; end -= 2) {
            grouped.insert(0, whole.substring(Math.max(0, end - 2), end) + ",");
        }

        return (value.signum() < 0 ? "-" : "") + "₹" + grouped + "." + parts[1];
    }

    /** "₹1,50,000" for "about" figures: whole rupees, no paise. */
    public static String whole(BigDecimal amount) {
        return of((amount == null ? BigDecimal.ZERO : amount).setScale(0, RoundingMode.HALF_UP)).replaceFirst("\\.00$", "");
    }

    public static String date(LocalDate date) {
        return date == null ? "" : DAY.format(date);
    }
}
