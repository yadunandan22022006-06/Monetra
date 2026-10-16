package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The deterministic half of the Financial Guardian. Every number comes from
 * the user's saved records and plain arithmetic; the AI never calculates.
 */
public final class BudgetRules {

    private static final BigDecimal LOW_FRACTION = new BigDecimal("0.20");
    private static final int EMI_WARNING_DAYS = 7;
    private static final String[] PERIODS = {"daily", "weekly", "monthly", "yearly"};
    private static final int MAX_LISTED = 8;
    private static final int EMI_WINDOW_DAYS = 30;

    private BudgetRules() {
    }

    /**
     * @param data   the user's saved records (may be null)
     * @param amount the payment being considered, in INR
     * @param today  the date used for "today", "this week", "this month" and "this year"
     */
    public static Map<String, Object> check(JsonNode data, BigDecimal amount, LocalDate today) {

        JsonNode safe = data == null ? MissingNode.getInstance() : data;

        String todayText = today.toString();
        String monthText = todayText.substring(0, 7);
        String yearText = todayText.substring(0, 4);
        String weekStart = today.minusDays(6).toString();

        BigDecimal spentToday = BigDecimal.ZERO;
        BigDecimal spentWeek = BigDecimal.ZERO;
        BigDecimal spentMonth = BigDecimal.ZERO;
        BigDecimal spentYear = BigDecimal.ZERO;
        BigDecimal incomeMonth = BigDecimal.ZERO;

        for (JsonNode item : safe.path("expenses")) {
            if (!countsTowardsLimits(item)) {
                continue;
            }

            String date = item.path("date").asText("");
            BigDecimal value = decimal(item.path("amount"));

            if (date.equals(todayText)) {
                spentToday = spentToday.add(value);
            }

            if (date.compareTo(weekStart) >= 0 && date.compareTo(todayText) <= 0) {
                spentWeek = spentWeek.add(value);
            }

            if (date.startsWith(monthText)) {
                spentMonth = spentMonth.add(value);
            }

            if (date.startsWith(yearText)) {
                spentYear = spentYear.add(value);
            }
        }

        for (JsonNode item : safe.path("income")) {
            if (item.path("date").asText("").startsWith(monthText)) {
                incomeMonth = incomeMonth.add(decimal(item.path("amount")));
            }
        }

        BigDecimal[] spent = {spentToday, spentWeek, spentMonth, spentYear};

        /* One result per limit the user has set. */

        List<Map<String, Object>> periods = new ArrayList<>();
        List<String> breaches = new ArrayList<>();
        String level = "unknown";

        for (int i = 0; i < PERIODS.length; i++) {
            BigDecimal limit = limit(safe, PERIODS[i]);

            if (limit.signum() <= 0) {
                continue;
            }

            BigDecimal after = limit.subtract(spent[i].add(amount));
            String periodLevel = levelFor(limit, after);

            Map<String, Object> row = new HashMap<>();
            row.put("period", PERIODS[i]);
            row.put("limit", money(limit));
            row.put("spent", money(spent[i]));
            row.put("remainingAfter", money(after));
            row.put("level", periodLevel);
            periods.add(row);

            if ("over".equals(periodLevel)) {
                breaches.add(PERIODS[i]);
            }

            level = worse(level, periodLevel);
        }

        /* Daily figures keep their own fields for the payment review screen. */

        BigDecimal dailyLimit = limit(safe, "daily");
        BigDecimal dailyAfter = dailyLimit.signum() > 0
                ? dailyLimit.subtract(spentToday.add(amount))
                : null;

        /* Next unpaid EMI on or after today. */

        String emiName = "";
        BigDecimal emiAmount = BigDecimal.ZERO;
        String emiDue = "";

        for (JsonNode loan : safe.path("loans")) {
            String due = loan.path("due").asText("");

            if (due.compareTo(todayText) >= 0 && (emiDue.isEmpty() || due.compareTo(emiDue) < 0)) {
                emiDue = due;
                emiName = loan.path("name").asText("");
                emiAmount = decimal(loan.path("amount"));
            }
        }

        /* Money left this month after this payment, and EMI pressure.
           The budget is the monthly limit when one is set, otherwise this month's income. */

        BigDecimal monthlyLimit = limit(safe, "monthly");
        BigDecimal budget = monthlyLimit.signum() > 0 ? monthlyLimit : incomeMonth;
        BigDecimal monthLeft = budget.subtract(spentMonth).subtract(amount);

        boolean emiSoon;

        try {
            emiSoon = !emiDue.isEmpty()
                    && !LocalDate.parse(emiDue).isAfter(today.plusDays(EMI_WARNING_DAYS));
        } catch (Exception e) {
            emiSoon = false;
        }

        boolean emiAtRisk = emiSoon
                && budget.signum() > 0
                && monthLeft.compareTo(emiAmount) < 0;

        Map<String, Object> out = new HashMap<>();
        out.put("level", level);
        out.put("amountInr", money(amount));
        out.put("dailyLimit", money(dailyLimit));
        out.put("spentToday", money(spentToday));
        out.put("remainingAfter", dailyAfter == null ? null : money(dailyAfter));
        out.put("periods", periods);
        out.put("breaches", breaches);
        out.put("monthIncome", money(incomeMonth));
        out.put("monthSpent", money(spentMonth));
        out.put("monthLeftAfter", money(monthLeft));
        out.put("nextEmiName", emiName);
        out.put("nextEmiAmount", money(emiAmount));
        out.put("nextEmiDue", emiDue);
        out.put("emiDueSoon", emiSoon);
        out.put("emiAtRisk", emiAtRisk);
        return out;
    }

    /**
     * Everything the Guardian AI is shown before a payment: every spending limit, every loan and EMI,
     * the savings goals and this month's totals. The AI only puts these figures into words.
     */
    public static Map<String, Object> paymentFacts(JsonNode data, BigDecimal amount, LocalDate today) {

        JsonNode safe = data == null ? MissingNode.getInstance() : data;

        Map<String, Object> check = check(safe, amount, today);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("today", today.toString());
        out.put("paymentAmountInr", money(amount));
        out.put("budgetStatus", check.get("level"));

        /* Limits: what was set, what is already spent, and what this payment does to each. */

        List<Map<String, Object>> limits = new ArrayList<>();

        for (Object o : (List<?>) check.get("periods")) {
            Map<?, ?> row = (Map<?, ?>) o;

            BigDecimal limit = (BigDecimal) row.get("limit");
            BigDecimal after = (BigDecimal) row.get("remainingAfter");

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", row.get("period"));
            m.put("limitInr", limit);
            m.put("spentBeforeThisPaymentInr", row.get("spent"));
            m.put("status", row.get("level"));

            if (after.signum() < 0) {
                m.put("overLimitByInr", after.negate());
                m.put("paymentIsTimesTheLimit", amount.divide(limit, 1, RoundingMode.HALF_UP));
            } else {
                m.put("leftAfterThisPaymentInr", after);
            }

            limits.add(m);
        }

        List<String> notSet = new ArrayList<>();

        for (String period : PERIODS) {
            if (limit(safe, period).signum() <= 0) {
                notSet.add(period);
            }
        }

        out.put("limits", limits);
        out.put("limitsNotSet", notSet);

        /* Loans and EMIs. An EMI is paid every month. */

        List<Map<String, Object>> loans = new ArrayList<>();
        BigDecimal emiPerMonth = BigDecimal.ZERO;
        BigDecimal emiDueSoon = BigDecimal.ZERO;

        for (JsonNode loan : safe.path("loans")) {
            BigDecimal emi = decimal(loan.path("amount"));

            emiPerMonth = emiPerMonth.add(emi);

            Long days = null;

            try {
                days = ChronoUnit.DAYS.between(today, LocalDate.parse(loan.path("due").asText("")));

            } catch (Exception e) {
                /* a loan without a valid date is listed without a countdown */
            }

            /* Overdue EMIs and the ones due in the next 30 days still have to be paid. */

            if (days != null && days <= EMI_WINDOW_DAYS) {
                emiDueSoon = emiDueSoon.add(emi);
            }

            if (loans.size() >= MAX_LISTED) {
                continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", clip(loan.path("name").asText("")));
            m.put("emiInr", money(emi));
            m.put("dueDate", loan.path("due").asText(""));

            if (days != null) {
                m.put(days < 0 ? "overdueByDays" : "dueInDays", Math.abs(days));
            }

            loans.add(m);
        }

        BigDecimal emiPerYear = emiPerMonth.multiply(BigDecimal.valueOf(12));

        out.put("loans", loans);
        out.put("emiDueWithin30DaysInr", money(emiDueSoon));
        out.put("emiTotalPerMonthInr", money(emiPerMonth));
        out.put("emiTotalPerYearInr", money(emiPerYear));
        out.put("emiNeedsSavingPerDayInr", emiPerYear.divide(BigDecimal.valueOf(365), 2, RoundingMode.HALF_UP));
        out.put("anEmiIsDueWithin7Days", check.get("emiDueSoon"));
        out.put("thisPaymentMayLeaveTooLittleForThatEmi", check.get("emiAtRisk"));

        /* Savings goals. */

        List<Map<String, Object>> goals = new ArrayList<>();
        BigDecimal stillNeeded = BigDecimal.ZERO;

        for (JsonNode goal : safe.path("goals")) {
            BigDecimal target = decimal(goal.path("target"));
            BigDecimal saved = decimal(goal.path("saved"));
            BigDecimal missing = target.subtract(saved).max(BigDecimal.ZERO);

            stillNeeded = stillNeeded.add(missing);

            if (goals.size() >= MAX_LISTED) {
                continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", clip(goal.path("name").asText("")));
            m.put("targetInr", money(target));
            m.put("savedInr", money(saved));
            m.put("stillNeededInr", money(missing));
            goals.add(m);
        }

        out.put("savingsGoals", goals);
        out.put("goalsStillNeededInr", money(stillNeeded));

        /* This month. The budget is the monthly limit when one is set, otherwise this month's income. */

        out.put("spentThisMonthInr", check.get("monthSpent"));

        if (limit(safe, "monthly").signum() > 0 || ((BigDecimal) check.get("monthIncome")).signum() > 0) {
            out.put("monthBudgetLeftAfterThisPaymentInr", check.get("monthLeftAfter"));
        }

        return out;
    }

    /**
     * Spending limits are about everyday spending. An EMI the user marked as paid is a commitment that
     * the Guardian already tracks on its own, so it lowers the balance but does not use up a limit.
     */
    static boolean countsTowardsLimits(JsonNode expense) {
        return !"emi".equals(expense.path("source").asText(""));
    }

    private static String clip(String text) {
        String s = text.trim();

        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    /**
     * Month-end forecast: if the spending pace so far this month continues, where does it end up?
     */
    public static Map<String, Object> forecast(JsonNode data, LocalDate today) {

        JsonNode safe = data == null ? MissingNode.getInstance() : data;

        String monthText = today.toString().substring(0, 7);

        BigDecimal spentMonth = BigDecimal.ZERO;

        for (JsonNode item : safe.path("expenses")) {
            if (countsTowardsLimits(item) && item.path("date").asText("").startsWith(monthText)) {
                spentMonth = spentMonth.add(decimal(item.path("amount")));
            }
        }

        int elapsed = today.getDayOfMonth();
        int daysInMonth = today.lengthOfMonth();

        /* The average is shown to two places, but the projection is worked out from the exact sum, so a rounded
           average never adds up to ₹0.15 of error: spent x days in the month / days so far. */
        BigDecimal avgDaily = spentMonth.divide(BigDecimal.valueOf(elapsed), 2, RoundingMode.HALF_UP);
        BigDecimal projected = spentMonth.multiply(BigDecimal.valueOf(daysInMonth))
                .divide(BigDecimal.valueOf(elapsed), 2, RoundingMode.HALF_UP);
        BigDecimal limit = limit(safe, "monthly");

        String status;

        if (limit.signum() <= 0) {
            status = "unknown";
        } else if (projected.compareTo(limit) > 0) {
            status = "over";
        } else if (projected.compareTo(limit.multiply(new BigDecimal("0.85"))) > 0) {
            status = "watch";
        } else {
            status = "on_track";
        }

        Object runOutDay = null;

        if (limit.signum() > 0 && spentMonth.signum() > 0) {
            /* The day the limit would run out: limit / (spent per day), from the exact sum. */
            BigDecimal days = limit.multiply(BigDecimal.valueOf(elapsed)).divide(spentMonth, 0, RoundingMode.FLOOR);

            if (days.compareTo(BigDecimal.valueOf(daysInMonth)) <= 0) {
                /* Spending faster than the whole limit a day means it ran out on the first day, never "day 0"
                   (the page reads 0 as "not this month"). */
                runOutDay = Math.max(1, days.intValue());
            }
        }

        Map<String, Object> out = new HashMap<>();
        out.put("status", status);
        out.put("spentThisMonth", money(spentMonth));
        out.put("dayOfMonth", elapsed);
        out.put("daysInMonth", daysInMonth);
        out.put("averagePerDay", money(avgDaily));
        out.put("projectedMonthEnd", money(projected));
        out.put("monthlyLimit", money(limit));
        out.put("limitRunsOutOnDay", runOutDay);
        return out;
    }

    private static String levelFor(BigDecimal limit, BigDecimal after) {
        if (after.signum() < 0) {
            return "over";
        }

        return after.compareTo(limit.multiply(LOW_FRACTION)) < 0 ? "low" : "ok";
    }

    private static String worse(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String level) {
        switch (level) {
            case "over":
                return 3;
            case "low":
                return 2;
            case "ok":
                return 1;
            default:
                return 0;
        }
    }

    /** The limit for a period from limits.{period}, falling back to the old dailyLimit field. */
    static BigDecimal limit(JsonNode data, String period) {
        BigDecimal value = decimal(data.path("limits").path(period));

        if (value.signum() <= 0 && "daily".equals(period)) {
            value = decimal(data.path("dailyLimit"));
        }

        return value;
    }

    static BigDecimal decimal(JsonNode node) {
        try {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return BigDecimal.ZERO;
            }

            BigDecimal value = new BigDecimal(node.asText("0"));

            return value.signum() < 0 ? BigDecimal.ZERO : value;

        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
