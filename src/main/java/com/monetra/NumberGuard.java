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

    /** Words that say a payment is fine as it is ("to stay within your limit" is advice, not one of them). */
    private static final Pattern SAYS_IT_FITS = Pattern.compile(
            "(is|are|still|remains|well) within|\\bfits\\b|is inside|are inside|is under your|are under your|close to"
                    + "|looks fine|is fine|is safe|is okay|is ok\\b");

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

    /**
     * True unless advice waves a risky payment through. Advice does not repeat the facts, because the
     * user already sees each of them in its own box. But when a limit is broken, or something is NOT
     * enough or NOT covered, it must not call the payment fine; and when no limit is broken it must not
     * claim that one is.
     */
    public static boolean fitsAdvice(String reply, boolean overLimit, String facts) {
        String text = reply == null ? "" : reply.toLowerCase(Locale.ROOT).replace('’', '\'');

        boolean shortfall = facts != null && (facts.contains("NOT enough") || facts.contains("NOT covered"));

        if (overLimit || shortfall) {
            return !SAYS_IT_FITS.matcher(text).find() && !SAYS_FINE.matcher(text).find();
        }

        return agreesWithVerdict(reply, false);
    }

    /**
     * For the buy planner: when buying today is not safe, the reply must not say it can be bought
     * today. A sentence that says "not" ("buying it today is not safe") is fine.
     */
    public static boolean keepsBuyDay(String reply, boolean safeToday) {
        if (safeToday) {
            return true;
        }

        String text = reply == null ? "" : reply.toLowerCase(Locale.ROOT).replace('’', '\'');

        for (String part : text.split("[.!?;\\n]")) {
            if (TODAY.matcher(part).find() && SAYS_YES.matcher(part).find() && !NEGATION.matcher(part).find()) {
                return false;
            }
        }

        return true;
    }

    /**
     * For Scam Shield: when the rules found a HIGH or MEDIUM risk, the reply must not call the message
     * safe or genuine ("this is not safe" is fine).
     */
    public static boolean keepsScamRisk(String reply, String level) {
        if (!"HIGH".equals(level) && !"MEDIUM".equals(level)) {
            return true;
        }

        String text = reply == null ? "" : reply.toLowerCase(Locale.ROOT).replace('’', '\'');

        for (String part : text.split("[.!?;\\n]")) {
            Matcher m = SAYS_GENUINE.matcher(part);

            if (m.find() && !NEGATION.matcher(part.substring(0, m.start())).find()) {
                return false;
            }
        }

        return true;
    }

    private static final Pattern SAYS_GENUINE = Pattern.compile(
            "\\b(is|looks|seems|appears) (to be )?(safe|genuine|legit|legitimate|real|trustworthy|fine)\\b"
                    + "|not a scam|nothing to worry|no risk|you can trust");

    private static final Pattern TODAY = Pattern.compile("\\b(today|right now|right away|immediately)\\b");

    private static final Pattern SAYS_YES = Pattern.compile("\\b(can|could|able to|afford|go ahead|fine|safe|okay|ok)\\b");

    /**
     * True when the reply copies two or more of the facts' sentences. Those sentences are already on
     * screen in their own boxes, so a reply that lists them again only says everything twice.
     */
    public static boolean repeatsFacts(String reply, String facts) {
        String text = squash(reply);

        int copied = 0;

        /* Sentences end at a full stop followed by a space, so "₹100.00" stays whole. */
        for (String fact : (facts == null ? "" : facts).split("\\n|(?<=[.!?])\\s+")) {
            String sentence = squash(fact).replaceAll("[.!?]+$", "");

            if (sentence.length() >= 25 && text.contains(sentence)) {
                copied++;
            }
        }

        return copied >= 2;
    }

    /** Lower case with single spaces, so the same sentence matches however it is spaced. */
    private static String squash(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("\\s+", " ").trim();
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
