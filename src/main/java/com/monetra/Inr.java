package com.monetra;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.NumberFormat;
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
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);

        DecimalFormat format = (DecimalFormat) NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));

        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);

        return (value.signum() < 0 ? "-" : "") + "₹" + format.format(value.abs());
    }

    public static String date(LocalDate date) {
        return date == null ? "" : DAY.format(date);
    }
}
