package com.monetra;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the AI honest about numbers. Java calculates every figure and hands it to the AI as facts;
 * the AI may only repeat those figures. If its reply contains any number that is not in the facts,
 * the reply is thrown away and the app shows its rule-based advice instead.
 */
public final class NumberGuard {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private NumberGuard() {
    }

    /** True when every number in the reply also appears in the facts ("1,500.00" and "1500" are the same). */
    public static boolean onlyKnownNumbers(String reply, String facts) {
        Set<BigDecimal> known = numbers(facts);

        for (BigDecimal n : numbers(reply)) {
            if (!known.contains(n)) {
                return false;
            }
        }

        return true;
    }

    private static Set<BigDecimal> numbers(String text) {
        Set<BigDecimal> out = new HashSet<>();

        Matcher m = NUMBER.matcher(text == null ? "" : text);

        while (m.find()) {
            String digits = m.group().replace(",", "");

            try {
                BigDecimal n = new BigDecimal(digits).stripTrailingZeros();

                out.add(n.scale() < 0 ? n.setScale(0) : n);

            } catch (NumberFormatException e) {
                /* not a number after all */
            }
        }

        return out;
    }
}
