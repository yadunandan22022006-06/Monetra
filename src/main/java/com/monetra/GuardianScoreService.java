package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Guardian Score: how well the user's money is protected today, out of 100. It is worked out
 * here in Java from the user's own saved records, with fixed rules (version 1). The AI never decides
 * a number.
 *
 *   Daily Spending Discipline   25   days within the daily limit over the last 7 days
 *   EMI Preparedness            25   whether the known balance covers the EMIs due before the next income
 *   Essential Bills Management  20   household bills due in the last 30 days that are not overdue
 *   Savings Goals Progress      20   how close each savings goal is to its plan
 *   Payment Risk Management     10   flagged payments that still went through in the last 30 days
 *
 * Missing data never earns points: a part the user has not filled in scores 0 and says what to add.
 * The level and badge come from the total. Each badge is recorded once, and the highest level ever
 * reached is kept apart from the current one, so a lower score never takes a badge away.
 *
 * Next to the levels there are daily and weekly badges: a Good Day badge for each finished day spent
 * within the daily limit, and a Good Week badge for a Monday-to-Sunday week with at least 5 Good Days.
 * Every finished day and week is decided once and kept, earned or not.
 */
@Service
public class GuardianScoreService {

    public static final String RULES_VERSION = "1";

    private static final Logger log = LoggerFactory.getLogger(GuardianScoreService.class);

    /** The five parts, in their fixed order. Equal parts keep this order when sorted. */
    static final List<String> ORDER = List.of("DAILY", "EMI", "BILLS", "SAVINGS", "RISK");

    /** The notification kinds that belong to the Guardian Score; the notification screen shows them on their own side. */
    public static final List<String> NOTIFICATION_TYPES = List.of("SCORE_DAILY", "BADGE_EARNED", "DAY_BADGE", "WEEK_BADGE");

    /** A week earns its Good Week badge with this many Good Days, Monday to Sunday. */
    static final int GOOD_WEEK_DAYS = 5;

    /** At most this many finished days back are decided, for example after the app was not running. */
    private static final int DAYS_BACK = 7;

    private static final String DAILY = "Daily Spending Discipline";
    private static final String EMI = "EMI Preparedness";
    private static final String BILLS = "Essential Bills Management";
    private static final String SAVINGS = "Savings Goals Progress";
    private static final String RISK = "Payment Risk Management";

    /** The achievement levels, lowest first. */
    public enum Level {
        BEGINNER(0, "Guardian Beginner", "Beginner Badge"),
        BUILDER(40, "Guardian Builder", "Builder Badge"),
        PROTECTOR(60, "Guardian Protector", "Protector Badge"),
        CHAMPION(80, "Guardian Champion", "Champion Badge"),
        ELITE(95, "Guardian Elite", "Elite Badge");

        public final int min;
        public final String title;
        public final String badge;

        Level(int min, String title, String badge) {
            this.min = min;
            this.title = title;
            this.badge = badge;
        }

        /** The highest score of this level. */
        public int max() {
            Level[] all = values();

            return ordinal() + 1 < all.length ? all[ordinal() + 1].min - 1 : 100;
        }

        public static Level of(int score) {
            Level found = BEGINNER;

            for (Level l : values()) {
                if (score >= l.min) {
                    found = l;
                }
            }

            return found;
        }

        static Level parse(String name) {
            if (name == null) {
                return null;
            }

            try {
                return valueOf(name);

            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * One part of the score. The points always stay between 0 and the part's maximum.
     *
     * @param advice   what would raise it, or null when nothing needs doing
     * @param gain     about how many points following the advice could add, worked out with the same rules, or null
     * @param priority how important the advice is: 1 is the most important
     */
    public record Category(String id, String name, int earned, int max, String explanation, String advice,
                           Integer gain, int priority) {

        public Category {
            earned = Math.max(0, Math.min(max, earned));
        }
    }

    /** A tip to raise the score. gain is an estimate, not a promise. */
    public record Tip(String category, String text, Integer gain, int priority) {
    }

    /** The score for one day, or no score (score null) when there is not enough data. */
    public record Result(Integer score, String confidence, String confidenceText, List<Category> categories,
                         List<Tip> tips, LocalDate day) {

        public boolean available() {
            return score != null;
        }
    }

    private final GuardianAnalysisService guardian;
    private final HomeExpenseService home;
    private final PaymentRepository payments;
    private final UserAccountRepository users;
    private final GuardianAlertRepository alerts;
    private final GuardianScoreStateRepository states;
    private final GuardianScoreRecordRepository history;
    private final GuardianBadgeRepository badges;
    private final GuardianPeriodBadgeRepository periodBadges;
    private final TransactionTemplate tx;

    /** One lock per user while their days and weeks are decided. */
    private final Map<Long, Object> deciding = new ConcurrentHashMap<>();

    @Value("${monetra.score.timezone:Asia/Kolkata}")
    private String defaultZone = "Asia/Kolkata";

    /** The daily update is created from this hour of the user's own day. */
    @Value("${monetra.score.daily-hour:9}")
    private int dailyHour = 9;

    @Value("${monetra.score.daily-job:true}")
    private boolean dailyJob = true;

    public GuardianScoreService(
            GuardianAnalysisService guardian,
            HomeExpenseService home,
            PaymentRepository payments,
            UserAccountRepository users,
            GuardianAlertRepository alerts,
            GuardianScoreStateRepository states,
            GuardianScoreRecordRepository history,
            GuardianBadgeRepository badges,
            GuardianPeriodBadgeRepository periodBadges,
            PlatformTransactionManager transactions) {

        this.guardian = guardian;
        this.home = home;
        this.payments = payments;
        this.users = users;
        this.alerts = alerts;
        this.states = states;
        this.history = history;
        this.badges = badges;
        this.periodBadges = periodBadges;
        this.tx = new TransactionTemplate(transactions);
    }

    /* ------------------------------------------------------------------ */
    /* Working out the score                                               */
    /* ------------------------------------------------------------------ */

    /** The score for the user's own day, from their saved records. Nothing is saved here. */
    public Result calculate(Long userId, Instant now) {
        ZoneId zone = zone(states.findByUserId(userId).orElse(null));
        LocalDate today = now.atZone(zone).toLocalDate();

        JsonNode data = guardian.data(userId);
        Map<String, Object> overview = guardian.overview(userId, today);

        Map<String, Object> money = map(overview.get("money"));
        Map<String, Object> loans = map(overview.get("loans"));
        List<Map<String, Object>> goals = list(overview.get("goals"));
        Map<String, Object> safe = map(overview.get("safeToSpend"));

        boolean balanceKnown = Boolean.TRUE.equals(money.get("balanceKnown"));
        boolean limitSet = BudgetRules.limit(data, "daily").signum() > 0;
        boolean anyBills = !home.list(userId).isEmpty();
        int loanCount = number(loans.get("count"));

        if (!limitSet && !balanceKnown && loanCount == 0 && !anyBills && goals.isEmpty()) {
            return new Result(null, "INSUFFICIENT_DATA",
                    "Add your balance, a daily limit, your loans, bills or a savings goal to get a Guardian Score.",
                    List.of(), List.of(), today);
        }

        BigDecimal safeAmount = Boolean.TRUE.equals(safe.get("known")) && safe.get("amount") instanceof BigDecimal b ? b : null;

        LocalDate since = users.findById(userId)
                .map(u -> u.getCreatedAt() == null ? null : u.getCreatedAt().atZone(zone).toLocalDate())
                .orElse(null);

        List<PaymentRecord> mine = payments.findByUserIdOrderByCreatedAtDesc(userId);

        List<Category> categories = List.of(
                daily(data, today, since),
                emi(loans, balanceKnown),
                bills(anyBills, home.rows(userId, today.minusDays(30), today, today)),
                savings(goals, safeAmount),
                risk(mine, now));

        int score = 0;

        for (Category c : categories) {
            score += c.earned();
        }

        score = Math.max(0, Math.min(100, score));

        /* How far the figures can be trusted. */

        long balanceAt = data.path("profile").path("balanceAt").asLong(0);
        boolean stale = balanceKnown && balanceAt > 0 && Instant.ofEpochMilli(balanceAt).isBefore(now.minus(7, ChronoUnit.DAYS));

        int confirmed = 0;
        int waiting = 0;

        for (PaymentRecord p : mine) {
            if (!recent(p, now)) {
                continue;
            }

            if ("COMPLETED".equals(p.status)) {
                confirmed++;

            } else if ("CREATED".equals(p.status) || "AWAITING_APPROVAL".equals(p.status)) {
                waiting++;
            }
        }

        String confidence;
        String confidenceText;

        if (stale) {
            confidence = "STALE_DATA";
            confidenceText = "Your balance was last updated on " + Inr.date(Instant.ofEpochMilli(balanceAt).atZone(zone).toLocalDate())
                    + ". Update it in Spending, under My money.";

        } else if (confirmed > 0) {
            confidence = "PARTIALLY_VERIFIED";
            confidenceText = "Your payments are confirmed by PayPal. Your balance, limits, loans, bills and goals are figures you entered.";

        } else {
            /* VERIFIED is kept for when every figure comes from a verified source; Monetra has none yet. */
            confidence = "MANUAL_DATA";
            confidenceText = "Based on figures you entered yourself.";
        }

        List<Tip> tips = new ArrayList<>();

        for (Category c : categories) {
            if (c.advice() != null) {
                tips.add(new Tip(c.id(), c.advice(), c.gain(), c.priority()));
            }
        }

        if (waiting > 0) {
            tips.add(new Tip("RISK", waiting + (waiting == 1 ? " payment is" : " payments are")
                    + " still waiting for PayPal. They count only after PayPal confirms them.", null, 8));
        }

        tips.sort(Comparator.comparingInt((Tip t) -> t.priority())
                .thenComparing((Tip t) -> t.gain() == null ? 0 : -t.gain())
                .thenComparingInt((Tip t) -> ORDER.indexOf(t.category())));

        return new Result(score, confidence, confidenceText, categories, tips, today);
    }

    /* ---------- the five parts: pure rules, so the same records always give the same points ---------- */

    static int points(int max, BigDecimal fraction) {
        BigDecimal f = fraction.max(BigDecimal.ZERO).min(BigDecimal.ONE);

        return BigDecimal.valueOf(max).multiply(f).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    static BigDecimal ratio(long part, long whole) {
        return whole <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 6, RoundingMode.HALF_UP);
    }

    /** Spent on one day, the way the spending limits count it (EMIs are left out). */
    static BigDecimal spentOn(JsonNode data, LocalDate day) {
        String key = day.toString();
        BigDecimal total = BigDecimal.ZERO;

        for (JsonNode e : data.path("expenses")) {
            if (BudgetRules.countsTowardsLimits(e) && key.equals(e.path("date").asText(""))) {
                total = total.add(BudgetRules.decimal(e.path("amount")));
            }
        }

        return total;
    }

    /** 2 for a day within the limit, 1 for a day up to a quarter over it, 0 beyond that. */
    static int dayUnits(BigDecimal spent, BigDecimal limit) {
        if (spent.compareTo(limit) <= 0) {
            return 2;
        }

        return spent.compareTo(limit.multiply(new BigDecimal("1.25"))) <= 0 ? 1 : 0;
    }

    /** Daily Spending Discipline (25): the last 7 days, never counting days before the user joined. */
    static Category daily(JsonNode data, LocalDate today, LocalDate since) {
        BigDecimal limit = BudgetRules.limit(data, "daily");

        if (limit.signum() <= 0) {
            return new Category("DAILY", DAILY, 0, 25, "No daily spending limit is set.",
                    "Set a daily limit in Spending to earn these points.", null, 6);
        }

        LocalDate start = today.minusDays(6);

        if (since != null && since.isAfter(start)) {
            start = since.isAfter(today) ? today : since;
        }

        int days = (int) ChronoUnit.DAYS.between(start, today) + 1;
        int units = 0;
        int within = 0;

        for (LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            int u = dayUnits(spentOn(data, d), limit);

            units += u;

            if (u == 2) {
                within++;
            }
        }

        int earned = points(25, ratio(units, 2L * days));

        /* If tomorrow stays within the limit: a full window drops its oldest day, a short one grows. */

        int nextUnits = days == 7 ? units - dayUnits(spentOn(data, start), limit) + 2 : units + 2;
        int nextDays = days == 7 ? 7 : days + 1;
        int gain = Math.max(0, points(25, ratio(nextUnits, 2L * nextDays)) - earned);

        String explanation = "Within your " + Inr.of(limit) + " daily limit on " + within + " of the last "
                + (days == 1 ? "1 day." : days + " days.");

        BigDecimal spentToday = spentOn(data, today);

        if (spentToday.compareTo(limit) > 0) {
            return new Category("DAILY", DAILY, earned, 25, explanation,
                    "Today you are " + Inr.of(spentToday.subtract(limit)) + " over your daily limit: hold back non-essential spending.",
                    gain > 0 ? gain : null, 4);
        }

        if (earned < 25) {
            return new Category("DAILY", DAILY, earned, 25, explanation,
                    "Stay within " + Inr.of(limit) + " a day to raise this part day by day.", gain > 0 ? gain : null, 7);
        }

        return new Category("DAILY", DAILY, earned, 25, explanation, null, null, 9);
    }

    private static int emiPoints(String status) {
        return switch (status) {
            case "LOW" -> 25;
            case "MEDIUM" -> 15;
            case "HIGH" -> 5;
            default -> 0;
        };
    }

    /** EMI Preparedness (25): needs a known balance; an overdue EMI scores nothing. */
    static Category emi(Map<String, Object> loans, boolean balanceKnown) {
        int count = number(loans.get("count"));

        if (count == 0) {
            return balanceKnown
                    ? new Category("EMI", EMI, 25, 25, "No loans or EMIs are recorded, and your balance is known.", null, null, 9)
                    : new Category("EMI", EMI, 0, 25, "No loans are recorded and your balance is not known, so nothing can be checked.",
                            "Add your balance in Spending, under My money.", null, 6);
        }

        Map<String, Object> overdue = null;

        for (Map<String, Object> row : list(loans.get("calendar"))) {
            if (Boolean.TRUE.equals(row.get("overdue"))) {
                overdue = row;
                break;
            }
        }

        String status = String.valueOf(loans.get("status"));

        if (overdue != null) {
            String name = String.valueOf(overdue.get("name"));
            int covered = emiPoints(status);

            return new Category("EMI", EMI, 0, 25,
                    "Your EMI \"" + name + "\" was due on " + date(overdue.get("date")) + " and is not marked paid.",
                    "Pay the overdue EMI \"" + name + "\" first, then mark it paid in Loans.",
                    balanceKnown && covered > 0 ? covered : null, 1);
        }

        if (!balanceKnown) {
            return new Category("EMI", EMI, 0, 25, "Your EMIs cannot be checked without your balance.",
                    "Add your balance in Spending, under My money.", null, 6);
        }

        String until = String.valueOf(loans.getOrDefault("until", "before your next income"));

        return switch (status) {
            case "LOW" -> new Category("EMI", EMI, 25, 25, "Your balance covers the EMIs due " + until + ".", null, null, 9);
            case "MEDIUM" -> new Category("EMI", EMI, 15, 25,
                    "Your balance covers the EMIs due " + until + ", with very little left over.",
                    "Keep some money free until your next income.", null, 5);
            case "HIGH" -> new Category("EMI", EMI, 5, 25, "Your balance may not cover the EMIs due " + until + ".",
                    "Set money aside for your EMIs before any other spending.", null, 3);
            default -> new Category("EMI", EMI, 0, 25, "Your EMIs could not be checked.", null, null, 9);
        };
    }

    /** Essential Bills Management (20): due dates in the last 30 days that are not overdue. */
    static Category bills(boolean anyBills, List<HomeExpenseService.Row> due) {
        if (!anyBills) {
            return new Category("BILLS", BILLS, 0, 20, "No household bills are recorded.",
                    "Add your regular bills in Guardian, under Family & Home.", null, 6);
        }

        if (due.isEmpty()) {
            return new Category("BILLS", BILLS, 20, 20, "No bills were due in the last 30 days.", null, null, 9);
        }

        List<HomeExpenseService.Row> overdue = new ArrayList<>();

        for (HomeExpenseService.Row r : due) {
            if ("OVERDUE".equals(r.status())) {
                overdue.add(r);
            }
        }

        int d = due.size();
        int o = overdue.size();
        int earned = points(20, ratio(d - o, d));

        if (o == 0) {
            return new Category("BILLS", BILLS, earned, 20, d == 1
                    ? "The bill due in the last 30 days is paid or due today."
                    : "All " + d + " bills due in the last 30 days are paid or due today.", null, null, 9);
        }

        HomeExpenseService.Row first = overdue.get(0);
        int gain = points(20, ratio(d - o + 1, d)) - earned;

        return new Category("BILLS", BILLS, earned, 20,
                o + " of " + d + " bills due in the last 30 days " + (o == 1 ? "is" : "are") + " overdue.",
                "Pay the overdue bill \"" + first.expense().name + "\" (due " + Inr.date(first.due()) + ") first.",
                gain > 0 ? gain : null, 2);
    }

    /** How close one goal is to its plan, from 0 to 1. extra is money that would be added to it. */
    static BigDecimal goalShare(Map<String, Object> g, BigDecimal extra) {
        return switch (String.valueOf(g.get("status"))) {
            case "REACHED", "ON_TRACK" -> BigDecimal.ONE;
            case "NO_DATE" -> new BigDecimal("0.5");
            case "DATE_PASSED" -> new BigDecimal("0.25");
            case "BEHIND" -> {
                BigDecimal should = g.get("shouldHaveSaved") instanceof BigDecimal s ? s : null;
                BigDecimal saved = g.get("saved") instanceof BigDecimal s ? s : BigDecimal.ZERO;

                yield should == null || should.signum() <= 0
                        ? new BigDecimal("0.5")
                        : saved.add(extra).divide(should, 6, RoundingMode.HALF_UP).max(BigDecimal.ZERO).min(BigDecimal.ONE);
            }
            default -> BigDecimal.ZERO;
        };
    }

    private static int savingsPoints(List<Map<String, Object>> goals, Map<String, Object> boosted, BigDecimal extra) {
        BigDecimal sum = BigDecimal.ZERO;

        for (Map<String, Object> g : goals) {
            sum = sum.add(goalShare(g, g == boosted ? extra : BigDecimal.ZERO));
        }

        return points(20, sum.divide(BigDecimal.valueOf(goals.size()), 6, RoundingMode.HALF_UP));
    }

    /**
     * Savings Goals Progress (20). A catch-up amount is only suggested when it fits inside today's
     * safe-to-spend money, so it never puts EMIs or bills at risk.
     */
    static Category savings(List<Map<String, Object>> goals, BigDecimal safeAmount) {
        if (goals.isEmpty()) {
            return new Category("SAVINGS", SAVINGS, 0, 20, "No savings goal yet.", "Create a savings goal in Savings.", null, 6);
        }

        int earned = savingsPoints(goals, null, BigDecimal.ZERO);
        int onPlan = 0;

        Map<String, Object> behind = null;
        Map<String, Object> noDate = null;
        Map<String, Object> passed = null;

        for (Map<String, Object> g : goals) {
            String status = String.valueOf(g.get("status"));

            if ("REACHED".equals(status) || "ON_TRACK".equals(status)) {
                onPlan++;

            } else if ("BEHIND".equals(status) && behind == null) {
                behind = g;

            } else if ("NO_DATE".equals(status) && noDate == null) {
                noDate = g;

            } else if ("DATE_PASSED".equals(status) && passed == null) {
                passed = g;
            }
        }

        String explanation = goals.size() == 1
                ? (onPlan == 1 ? "Your savings goal is on plan." : "Your savings goal is not on plan.")
                : onPlan + " of " + goals.size() + " savings goals are on plan.";

        if (behind != null) {
            String name = String.valueOf(behind.get("name"));
            BigDecimal shortfall = behind.get("shortfall") instanceof BigDecimal s ? s : BigDecimal.ZERO;

            if (safeAmount != null && safeAmount.signum() > 0 && shortfall.signum() > 0) {
                BigDecimal add = BudgetRules.money(shortfall.min(safeAmount));
                int gain = savingsPoints(goals, behind, add) - earned;

                return new Category("SAVINGS", SAVINGS, earned, 20, explanation,
                        "Adding " + Inr.of(add) + " to \"" + name + "\" would bring it closer to its plan, and it fits within today's safe-to-spend money.",
                        gain > 0 ? gain : null, 5);
            }

            return new Category("SAVINGS", SAVINGS, earned, 20, explanation,
                    "\"" + name + "\" is " + Inr.of(shortfall) + " behind its plan. Catch up once your EMIs and bills are covered.", null, 5);
        }

        if (noDate != null) {
            return new Category("SAVINGS", SAVINGS, earned, 20, explanation,
                    "Give \"" + noDate.get("name") + "\" a target date so Monetra can plan it.", null, 7);
        }

        if (passed != null) {
            return new Category("SAVINGS", SAVINGS, earned, 20, explanation,
                    "Choose a new target date for \"" + passed.get("name") + "\".", null, 7);
        }

        return new Category("SAVINGS", SAVINGS, earned, 20, explanation, null, null, 9);
    }

    /** Payment Risk Management (10): payments the Fraud Guard flagged that PayPal still completed, last 30 days. */
    static Category risk(List<PaymentRecord> mine, Instant now) {
        int flagged = 0;

        for (PaymentRecord p : mine) {
            if ("COMPLETED".equals(p.status) && p.riskFlags != null && !p.riskFlags.isBlank() && recent(p, now)) {
                flagged++;
            }
        }

        if (flagged == 0) {
            return new Category("RISK", RISK, 10, 10, "No payment flagged by the Fraud Guard went through in the last 30 days.", null, null, 9);
        }

        return new Category("RISK", RISK, 10 - 4 * flagged, 10,
                flagged + (flagged == 1 ? " payment" : " payments") + " flagged by the Fraud Guard went through in the last 30 days.",
                "Before paying a flagged person again, check with them directly. These points come back as the payments pass 30 days.",
                null, 4);
    }

    private static boolean recent(PaymentRecord p, Instant now) {
        try {
            return p.createdAt != null && !Instant.parse(p.createdAt).isBefore(now.minus(30, ChronoUnit.DAYS));

        } catch (DateTimeException e) {
            return false;
        }
    }

    /** Highest share of points first, or lowest first. Then by points, then in the fixed order. */
    public static List<Category> sorted(List<Category> list, boolean highFirst) {
        Comparator<Category> byShare = (a, b) -> Integer.compare(a.earned() * b.max(), b.earned() * a.max());
        Comparator<Category> byPoints = Comparator.comparingInt((Category c) -> c.earned());

        Comparator<Category> order = highFirst
                ? byShare.reversed().thenComparing(byPoints.reversed())
                : byShare.thenComparing(byPoints);

        return list.stream()
                .sorted(order.thenComparingInt((Category c) -> ORDER.indexOf(c.id())))
                .toList();
    }

    /* ------------------------------------------------------------------ */
    /* Recording: score history, level, badges, notifications              */
    /* ------------------------------------------------------------------ */

    /**
     * Works the score out again, records it and awards any badge reached for the first time. A newly
     * earned badge gets its own notification straight away. Safe to call as often as wanted.
     */
    public Map<String, Object> refresh(Long userId, Instant now) {
        try {
            decidePeriods(userId, now);

        } catch (RuntimeException e) {
            /* The daily and weekly badges are tried again on the next refresh; the score still shows. */
            log.warn("Daily and weekly badges could not be decided for user {}", userId, e);
        }

        Result r = calculate(userId, now);

        List<Level> earned = record(userId, r, now);

        Map<String, Object> newBadge = null;

        if (!earned.isEmpty() && r.available()) {
            Level top = earned.get(earned.size() - 1);

            newBadge = level(top);

            if (prefs(userId).path("badges").asBoolean(true)) {
                notifyOnce(userId, "BADGE:" + top.name(), "BADGE_EARNED",
                        "You earned the " + top.title + " badge. Your Guardian Score is now " + r.score()
                                + "/100. Keep making informed money decisions with Monetra.");
            }
        }

        return payload(userId, r, newBadge);
    }

    /** Refreshes the score after the user's money changed. Never stops the change itself. */
    public void refreshQuietly(Long userId) {
        if (userId == null) {
            return;
        }

        try {
            refresh(userId, Instant.now());

        } catch (RuntimeException e) {
            log.warn("The Guardian Score could not be refreshed for user {}", userId, e);
        }
    }

    /** Saves the day's score, the level and new badges in one transaction; tried again if another request got there first. */
    private List<Level> record(Long userId, Result r, Instant now) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                List<Level> out = tx.execute(status -> recordOnce(userId, r, now));

                return out == null ? List.of() : out;

            } catch (RuntimeException e) {
                /* A unique key or version clash: look again with what the other request saved. */
                log.debug("Guardian Score record retry for user {}", userId, e);
            }
        }

        return List.of();
    }

    private List<Level> recordOnce(Long userId, Result r, Instant now) {
        GuardianScoreState state = states.findByUserId(userId).orElseGet(() -> {
            GuardianScoreState s = new GuardianScoreState();
            s.userId = userId;
            return s;
        });

        List<Level> earned = new ArrayList<>();

        if (r.available()) {
            int score = r.score();

            GuardianScoreRecord day = history.findByUserIdAndDay(userId, r.day()).orElseGet(() -> {
                GuardianScoreRecord h = new GuardianScoreRecord();
                h.userId = userId;
                h.day = r.day();
                return h;
            });

            day.score = score;
            day.confidence = r.confidence();
            day.rulesVersion = RULES_VERSION;
            day.updatedAt = now;

            history.save(day);

            Level level = Level.of(score);

            Set<String> have = new HashSet<>();

            for (GuardianBadge b : badges.findByUserIdOrderByEarnedAtAsc(userId)) {
                have.add(b.level);
            }

            /* Every level up to this one that was never earned: crossed now, recorded now. */

            for (Level l : Level.values()) {
                if (l.ordinal() > level.ordinal()) {
                    break;
                }

                if (!have.contains(l.name())) {
                    GuardianBadge b = new GuardianBadge();
                    b.userId = userId;
                    b.level = l.name();
                    b.scoreWhenEarned = score;
                    b.earnedAt = now;

                    badges.save(b);
                    earned.add(l);
                }
            }

            state.currentLevel = level.name();

            Level highest = Level.parse(state.highestLevel);

            if (highest == null || level.ordinal() > highest.ordinal()) {
                state.highestLevel = level.name();
            }

        } else {
            /* No score, no current level. The highest level ever reached stays. */
            state.currentLevel = null;
        }

        state.lastScore = r.score();
        state.lastConfidence = r.confidence();
        state.lastCalculatedAt = now;

        states.save(state);

        return earned;
    }

    /** Creates a notification once, in a transaction: if saving fails nothing is kept, and the next run tries again. */
    private boolean notifyOnce(Long userId, String key, String type, String message) {
        try {
            Integer made = tx.execute(status -> guardian.notify(userId, key, type, message));

            return made != null && made > 0;

        } catch (RuntimeException e) {
            log.warn("Guardian notification {} for user {} was not saved; it will be tried again", key, userId, e);
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* The daily Guardian update                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Creates today's Guardian update for one user, once per day of their own time zone, from
     * the daily hour on. Asked by the background job and whenever the app loads its notifications;
     * the alert key makes sure only one is ever created for the day.
     */
    public boolean sendDailyIfDue(Long userId, Instant now) {
        if (!prefs(userId).path("scoreDaily").asBoolean(true)) {
            return false;
        }

        ZonedDateTime local = now.atZone(zone(states.findByUserId(userId).orElse(null)));

        if (local.getHour() < dailyHour) {
            return false;
        }

        LocalDate day = local.toLocalDate();
        String key = "SCORE_DAILY:" + day;

        if (alerts.existsByUserIdAndAlertKey(userId, key)) {
            return false;
        }

        Map<String, Object> score = refresh(userId, now);

        if (!(score.get("score") instanceof Integer value)) {
            /* No score can be worked out: nothing is made up, and nothing is sent. */
            return false;
        }

        String tip = "";

        if (score.get("tips") instanceof List<?> tips && !tips.isEmpty() && tips.get(0) instanceof Map<?, ?> first) {
            tip = String.valueOf(first.get("text"));
        }

        GuardianScoreState state = states.findByUserId(userId).orElse(null);
        String lastTip = state == null ? null : state.lastDailyTip;

        String shown = tip.isEmpty() || tip.equals(lastTip)
                ? "Look at your lowest-scoring part to see where you can improve."
                : tip;

        /* The page shows the title "Daily Guardian Update" above it. */
        String message = "Your Guardian Score is " + value + "/100, " + Level.of(value).title + ". Tip: " + shown;

        if (!notifyOnce(userId, key, "SCORE_DAILY", message)) {
            return false;
        }

        String savedTip = tip.length() > 200 ? tip.substring(0, 200) : tip;

        try {
            tx.executeWithoutResult(status -> states.findByUserId(userId).ifPresent(s -> {
                s.lastDailyDate = day;
                s.lastDailyTip = savedTip;
                states.save(s);
            }));

        } catch (RuntimeException e) {
            /* Only the record of the day's tip is lost; the notification itself is saved once. */
            log.debug("Could not note the daily update for user {}", userId, e);
        }

        return true;
    }

    /** The background job: every 10 minutes, finished days and weeks are decided and each user who is due gets their daily update. */
    @Scheduled(fixedDelayString = "${monetra.score.check-ms:600000}", initialDelayString = "${monetra.score.first-check-ms:60000}")
    public void dailyUpdates() {
        if (!dailyJob) {
            return;
        }

        Instant now = Instant.now();

        for (UserAccount u : users.findAll()) {
            try {
                decidePeriods(u.getId(), now);
                sendDailyIfDue(u.getId(), now);

            } catch (RuntimeException e) {
                log.warn("The daily Guardian update for user {} failed; it is tried again later", u.getId(), e);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Daily and weekly badges                                             */
    /* ------------------------------------------------------------------ */

    /** A Good Day: a daily limit is set and the day's spending stayed within it. */
    static boolean goodDay(BigDecimal spent, BigDecimal limit) {
        return limit != null && limit.signum() > 0 && spent != null && spent.compareTo(limit) <= 0;
    }

    /** A Good Week: at least 5 Good Days from Monday to Sunday. */
    static boolean goodWeek(int goodDays) {
        return goodDays >= GOOD_WEEK_DAYS;
    }

    /** The Monday that starts this day's week. */
    static LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * Decides each finished day (up to a week back, never before the user joined) and each finished
     * week that is not decided yet, and records it, earned or not. Only the day and the week that just
     * ended send a notification, so catching up never fills the list. Days are only decided while a
     * daily limit is set: without one there is nothing to keep to. Safe to call as often as wanted.
     */
    public void decidePeriods(Long userId, Instant now) {
        /* One user's days are decided one request at a time; the unique key still guards against any other copy of the app. */
        synchronized (deciding.computeIfAbsent(userId, id -> new Object())) {
            decideNow(userId, now);
        }
    }

    private void decideNow(Long userId, Instant now) {
        ZoneId zone = zone(states.findByUserId(userId).orElse(null));
        LocalDate today = now.atZone(zone).toLocalDate();
        LocalDate yesterday = today.minusDays(1);
        LocalDate lastWeek = weekStart(today).minusWeeks(1);

        JsonNode data = guardian.data(userId);
        BigDecimal limit = BudgetRules.limit(data, "daily");
        boolean tell = data.path("profile").path("notify").path("badges").asBoolean(true);

        LocalDate since = users.findById(userId)
                .map(u -> u.getCreatedAt() == null ? null : u.getCreatedAt().atZone(zone).toLocalDate())
                .orElse(null);

        LocalDate from = today.minusDays(DAYS_BACK);

        if (since != null && since.isAfter(from)) {
            from = since;
        }

        /* The days. */

        if (limit.signum() > 0) {
            Set<LocalDate> decided = new HashSet<>();

            for (GuardianPeriodBadge d : periodBadges.findByUserIdAndKindAndStartGreaterThanEqualOrderByStartAsc(userId, GuardianPeriodBadge.DAY, from)) {
                decided.add(d.start);
            }

            for (LocalDate d = from; d.isBefore(today); d = d.plusDays(1)) {
                if (decided.contains(d)) {
                    continue;
                }

                BigDecimal spent = spentOn(data, d);

                GuardianPeriodBadge day = new GuardianPeriodBadge();
                day.userId = userId;
                day.kind = GuardianPeriodBadge.DAY;
                day.start = d;
                day.earned = goodDay(spent, limit);
                day.spent = spent;
                day.limitAmount = limit;
                day.decidedAt = now;

                if (saveOnce(day) && day.earned && d.equals(yesterday) && tell) {
                    notifyOnce(userId, "DAY_BADGE:" + d, "DAY_BADGE",
                            "You earned the Good Day badge for " + Inr.date(d) + ": you spent " + Inr.of(spent)
                                    + " of your " + Inr.of(limit) + " daily limit.");
                }
            }
        }

        /* The last two finished weeks, from the days recorded for them. */

        LocalDate firstWeek = lastWeek.minusWeeks(1);

        Map<LocalDate, GuardianPeriodBadge> days = new HashMap<>();

        for (GuardianPeriodBadge d : periodBadges.findByUserIdAndKindAndStartGreaterThanEqualOrderByStartAsc(userId, GuardianPeriodBadge.DAY, firstWeek)) {
            days.put(d.start, d);
        }

        Set<LocalDate> weeksDone = new HashSet<>();

        for (GuardianPeriodBadge w : periodBadges.findByUserIdAndKindAndStartGreaterThanEqualOrderByStartAsc(userId, GuardianPeriodBadge.WEEK, firstWeek)) {
            weeksDone.add(w.start);
        }

        for (LocalDate w = firstWeek; !w.isAfter(lastWeek); w = w.plusWeeks(1)) {
            /* A week counts only when the user was there from its Monday: joining on a Sunday is no missed week. */
            if (weeksDone.contains(w) || (since != null && since.isAfter(w))) {
                continue;
            }

            int counted = 0;
            int good = 0;

            for (int i = 0; i < 7; i++) {
                GuardianPeriodBadge d = days.get(w.plusDays(i));

                if (d != null) {
                    counted++;

                    if (d.earned) {
                        good++;
                    }
                }
            }

            if (counted == 0) {
                continue;   /* nothing is known about that week */
            }

            GuardianPeriodBadge week = new GuardianPeriodBadge();
            week.userId = userId;
            week.kind = GuardianPeriodBadge.WEEK;
            week.start = w;
            week.earned = goodWeek(good);
            week.goodDays = good;
            week.days = counted;
            week.decidedAt = now;

            if (saveOnce(week) && week.earned && w.equals(lastWeek) && tell) {
                notifyOnce(userId, "WEEK_BADGE:" + w, "WEEK_BADGE",
                        "You earned the Good Week badge for " + Inr.date(w) + " to " + Inr.date(w.plusDays(6))
                                + ": within your daily limit on " + good + " of 7 days.");
            }
        }
    }

    /** Saves one decided day or week. False when another request decided it first. */
    private boolean saveOnce(GuardianPeriodBadge badge) {
        try {
            tx.executeWithoutResult(status -> periodBadges.saveAndFlush(badge));
            return true;

        } catch (RuntimeException e) {
            log.debug("Day or week {} {} was already decided for user {}", badge.kind, badge.start, badge.userId, e);
            return false;
        }
    }

    /** What the badge screen shows: today so far, the last 7 days, this week, past weeks and the totals. */
    Map<String, Object> periods(Long userId, LocalDate today) {
        JsonNode data = guardian.data(userId);
        BigDecimal limit = BudgetRules.limit(data, "daily");
        BigDecimal spentToday = spentOn(data, today);
        boolean limitSet = limit.signum() > 0;

        Map<String, Object> out = new LinkedHashMap<>();

        out.put("limitSet", limitSet);
        out.put("limit", limit);
        out.put("spentToday", spentToday);
        out.put("todayOnTrack", goodDay(spentToday, limit));

        LocalDate first = today.minusDays(6);
        LocalDate monday = weekStart(today);

        Map<LocalDate, GuardianPeriodBadge> decided = new HashMap<>();

        for (GuardianPeriodBadge d : periodBadges.findByUserIdAndKindAndStartGreaterThanEqualOrderByStartAsc(
                userId, GuardianPeriodBadge.DAY, first.isBefore(monday) ? first : monday)) {
            decided.put(d.start, d);
        }

        List<Map<String, Object>> days = new ArrayList<>();

        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
            GuardianPeriodBadge b = decided.get(d);

            String status;

            if (d.equals(today)) {
                status = !limitSet ? "NONE" : goodDay(spentToday, limit) ? "TODAY_ON_TRACK" : "TODAY_OVER";

            } else {
                status = b == null ? "NONE" : b.earned ? "EARNED" : "MISSED";
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.toString());
            m.put("status", status);
            m.put("spent", d.equals(today) ? spentToday : b == null ? null : b.spent);
            days.add(m);
        }

        out.put("days", days);

        /* This week so far: finished days only; today counts once it is over. */

        int good = 0;

        for (LocalDate d = monday; d.isBefore(today); d = d.plusDays(1)) {
            GuardianPeriodBadge b = decided.get(d);

            if (b != null && b.earned) {
                good++;
            }
        }

        out.put("weekGoodDays", good);
        out.put("weekNeeded", GOOD_WEEK_DAYS);
        out.put("weekDaysLeft", (int) ChronoUnit.DAYS.between(today, monday.plusDays(7)));

        List<Map<String, Object>> weeks = new ArrayList<>();

        for (GuardianPeriodBadge w : periodBadges.findTop8ByUserIdAndKindOrderByStartDesc(userId, GuardianPeriodBadge.WEEK)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("start", w.start.toString());
            m.put("end", w.start.plusDays(6).toString());
            m.put("goodDays", w.goodDays);
            m.put("days", w.days);
            m.put("earned", w.earned);
            weeks.add(m);
        }

        out.put("weeks", weeks);
        out.put("goodDaysTotal", periodBadges.countByUserIdAndKindAndEarnedTrue(userId, GuardianPeriodBadge.DAY));
        out.put("goodWeeksTotal", periodBadges.countByUserIdAndKindAndEarnedTrue(userId, GuardianPeriodBadge.WEEK));

        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Time zone, history, and what the page gets                          */
    /* ------------------------------------------------------------------ */

    /** Saves the user's own time zone, as their browser reports it. False when it is not a real zone. */
    public boolean setTimezone(Long userId, String timezone) {
        if (timezone == null || timezone.isBlank() || timezone.length() > 40) {
            return false;
        }

        try {
            ZoneId.of(timezone);

        } catch (DateTimeException e) {
            return false;
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                tx.executeWithoutResult(status -> {
                    GuardianScoreState s = states.findByUserId(userId).orElseGet(() -> {
                        GuardianScoreState n = new GuardianScoreState();
                        n.userId = userId;
                        return n;
                    });

                    if (!timezone.equals(s.timezone)) {
                        s.timezone = timezone;
                        states.save(s);
                    }
                });

                return true;

            } catch (RuntimeException e) {
                log.debug("Time zone save retry for user {}", userId, e);
            }
        }

        return true;
    }

    ZoneId zone(GuardianScoreState state) {
        try {
            return ZoneId.of(state != null && state.timezone != null ? state.timezone : defaultZone);

        } catch (DateTimeException e) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    /** The recorded daily scores, newest first. */
    public List<Map<String, Object>> history(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();

        for (GuardianScoreRecord h : history.findTop30ByUserIdOrderByDayDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", h.day.toString());
            m.put("score", h.score);
            m.put("confidence", h.confidence);
            out.add(m);
        }

        return out;
    }

    private static Map<String, Object> level(Level l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.name());
        m.put("title", l.title);
        m.put("badge", l.badge);
        m.put("min", l.min);
        m.put("max", l.max());
        return m;
    }

    /** Everything the Guardian Score screen shows. */
    Map<String, Object> payload(Long userId, Result r, Map<String, Object> newBadge) {
        GuardianScoreState state = states.findByUserId(userId).orElse(null);

        Map<String, Object> out = new LinkedHashMap<>();

        out.put("available", r.available());
        out.put("score", r.score());
        out.put("max", 100);
        out.put("rulesVersion", RULES_VERSION);
        out.put("day", r.day().toString());
        out.put("confidence", r.confidence());
        out.put("confidenceText", r.confidenceText());

        Level current = r.available() ? Level.of(r.score()) : null;

        out.put("level", current == null ? null : level(current));

        if (current != null && current.ordinal() + 1 < Level.values().length) {
            Level next = Level.values()[current.ordinal() + 1];

            Map<String, Object> m = level(next);
            m.put("pointsNeeded", next.min - r.score());

            out.put("nextLevel", m);

        } else {
            out.put("nextLevel", null);
        }

        Level highest = state == null ? null : Level.parse(state.highestLevel);

        out.put("highestLevel", highest == null ? null : level(highest));

        /* A one-line explanation: the strongest and the weakest part. */

        if (r.available()) {
            List<Category> high = sorted(r.categories(), true);
            Category best = high.get(0);
            Category worst = high.get(high.size() - 1);

            out.put("explanation", best == worst || best.earned() * worst.max() == worst.earned() * best.max()
                    ? "All five parts are at the same level."
                    : "Strongest: " + best.name() + ". Needs the most work: " + worst.name() + ".");

        } else {
            out.put("explanation", r.confidenceText());
        }

        List<Map<String, Object>> categories = new ArrayList<>();

        for (Category c : r.categories()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("name", c.name());
            m.put("earned", c.earned());
            m.put("max", c.max());
            m.put("percent", c.earned() * 100 / c.max());
            m.put("explanation", c.explanation());
            m.put("advice", c.advice());
            m.put("gain", c.gain());
            categories.add(m);
        }

        out.put("categories", categories);

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("high", sorted(r.categories(), true).stream().map(c -> c.id()).toList());
        order.put("low", sorted(r.categories(), false).stream().map(c -> c.id()).toList());

        out.put("order", order);

        List<Map<String, Object>> tips = new ArrayList<>();

        for (Tip t : r.tips()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", t.category());
            m.put("text", t.text());
            m.put("gain", t.gain());
            tips.add(m);
        }

        out.put("tips", tips);

        /* Badge history: only what was really recorded. */

        Map<String, GuardianBadge> have = new LinkedHashMap<>();

        for (GuardianBadge b : badges.findByUserIdOrderByEarnedAtAsc(userId)) {
            have.put(b.level, b);
        }

        List<Map<String, Object>> list = new ArrayList<>();

        for (Level l : Level.values()) {
            Map<String, Object> m = level(l);
            GuardianBadge b = have.get(l.name());

            m.put("status", b == null ? "NOT_EARNED" : l == current ? "CURRENT" : "EARNED");
            m.put("earnedAt", b == null ? null : b.earnedAt.toString());
            m.put("scoreWhenEarned", b == null ? null : b.scoreWhenEarned);

            list.add(m);
        }

        out.put("badges", list);
        out.put("anyBadge", !have.isEmpty());
        out.put("newBadge", newBadge);
        out.put("periods", periods(userId, r.day()));
        out.put("history", history(userId).stream().limit(14).toList());

        JsonNode prefs = prefs(userId);

        out.put("dailyUpdates", prefs.path("scoreDaily").asBoolean(true));
        out.put("badgeUpdates", prefs.path("badges").asBoolean(true));

        return out;
    }

    /* ------------------------------------------------------------------ */

    private JsonNode prefs(Long userId) {
        return guardian.data(userId).path("profile").path("notify");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();

        if (o instanceof List<?> l) {
            for (Object item : l) {
                if (item instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }

        return out;
    }

    private static int number(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    private static String date(Object iso) {
        try {
            return Inr.date(LocalDate.parse(String.valueOf(iso)));

        } catch (DateTimeException e) {
            return String.valueOf(iso);
        }
    }
}
