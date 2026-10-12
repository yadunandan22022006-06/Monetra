package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
     * Month-end forecast: if the spending pace so far this month continues, where does it end up?
     */
    public static Map<String, Object> forecast(JsonNode data, LocalDate today) {

        JsonNode safe = data == null ? MissingNode.getInstance() : data;

        String monthText = today.toString().substring(0, 7);

        BigDecimal spentMonth = BigDecimal.ZERO;

        for (JsonNode item : safe.path("expenses")) {
            if (item.path("date").asText("").startsWith(monthText)) {
                spentMonth = spentMonth.add(decimal(item.path("amount")));
            }
        }

        int elapsed = today.getDayOfMonth();
        int daysInMonth = today.lengthOfMonth();

        BigDecimal avgDaily = spentMonth.divide(BigDecimal.valueOf(elapsed), 2, RoundingMode.HALF_UP);
        BigDecimal projected = avgDaily.multiply(BigDecimal.valueOf(daysInMonth));
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

        if (limit.signum() > 0 && avgDaily.signum() > 0) {
            BigDecimal days = limit.divide(avgDaily, 0, RoundingMode.FLOOR);

            if (days.compareTo(BigDecimal.valueOf(daysInMonth)) <= 0) {
                runOutDay = days.intValue();
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
    private static BigDecimal limit(JsonNode data, String period) {
        BigDecimal value = decimal(data.path("limits").path(period));

        if (value.signum() <= 0 && "daily".equals(period)) {
            value = decimal(data.path("dailyLimit"));
        }

        return value;
    }

    private static BigDecimal decimal(JsonNode node) {
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

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
