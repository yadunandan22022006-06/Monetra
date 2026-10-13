package com.monetra;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the AI honest. Java calculates every figure and the verdict on the spending limits, and hands
 * them to the AI as facts; the AI may only put them into words. If its reply contains a number that is
 * not in the facts, or says the opposite of the verdict, the reply is thrown away and the app shows its
 * rule-based advice instead.
 */
public final class NumberGuard {

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    /** Words that say a limit is broken ("overdue" is not one of them). */
    private static final Pattern OVER = Pattern.compile(
            "exceed|goes over|\\bover (your|the|that|this|it|by)\\b|above your|beyond your");

    private static final Pattern NEGATION = Pattern.compile("\\b(not|never|without|no)\\b|n't");

    /** Words that say a limit is respected, or only nearly reached. */
    private static final Pattern SAYS_FITS = Pattern.compile(
            "(still|is|are|stays|remains|well) within|within it\\b|close to|\\bfits\\b|is inside|are inside|is under your|are under your"
                    + "|not exceed|n't exceed|not go over|n't go over");

    /** Words that say there is too little money for something. */
    private static final Pattern SAYS_SHORT = Pattern.compile(
            "not enough|n't enough|not be enough|not cover|n't cover|not be covered|not covered|too little|short of|fall short|falls short");

    /** Words that wave a payment through. */
    private static final Pattern SAYS_FINE = Pattern.compile("good to go|all set|no issues|without any issues|no problem");

    private NumberGuard() {
    }

    /**
     * True unless the reply drops a warning that is in the facts. When the facts say something is
     * NOT enough or NOT covered, the reply has to say so too and must not wave the payment through.
     */
    public static boolean keepsWarnings(String reply, String facts) {
        String text = reply == null ? "" : reply.toLowerCase(Locale.ROOT).replace('’', '\'');

        boolean shortfall = facts != null && (facts.contains("NOT enough") || facts.contains("NOT covered"));

        return !shortfall || (SAYS_SHORT.matcher(text).find() && !SAYS_FINE.matcher(text).find());
    }

    /**
     * True unless the reply contradicts Java's verdict on the spending limits. When a limit is broken
     * the reply has to say so plainly; when none is broken it must not claim that one is.
     */
    public static boolean agreesWithVerdict(String reply, boolean overLimit) {
        String text = reply == null ? "" : reply.toLowerCase(Locale.ROOT).replace('’', '\'');

        boolean saysOver = false;

        /* "It does not exceed your limit" is not a claim that the limit is broken: look at each part of a sentence. */

        for (String part : text.split("[.!?;,\\n]")) {
            Matcher m = OVER.matcher(part);

            if (m.find() && !NEGATION.matcher(part.substring(0, m.start())).find()) {
                saysOver = true;
            }
        }

        return overLimit
                ? saysOver && !SAYS_FITS.matcher(text).find() && !SAYS_FINE.matcher(text).find()
                : !saysOver;
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
