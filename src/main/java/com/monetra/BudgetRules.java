package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

        for (JsonNode loan : safe.path("loans")) {
            BigDecimal emi = decimal(loan.path("amount"));

            emiPerMonth = emiPerMonth.add(emi);

            if (loans.size() >= MAX_LISTED) {
                continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", clip(loan.path("name").asText("")));
            m.put("emiInr", money(emi));
            m.put("dueDate", loan.path("due").asText(""));

            try {
                long days = ChronoUnit.DAYS.between(today, LocalDate.parse(loan.path("due").asText("")));

                m.put(days < 0 ? "overdueByDays" : "dueInDays", Math.abs(days));

            } catch (Exception e) {
                /* a loan without a valid date is listed without a countdown */
            }

            loans.add(m);
        }

        BigDecimal emiPerYear = emiPerMonth.multiply(BigDecimal.valueOf(12));

        out.put("loans", loans);
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
     * The few facts that matter most for this payment, as short plain sentences. Java reads every
     * limit, loan, EMI, goal and bill and picks them; the AI only puts them into friendly words.
     * Keeping the list short is what makes a small, free model reliable.
     */
    public static List<String> paymentBrief(Map<String, Object> facts) {
        List<String> lines = new ArrayList<>();

        String to = String.valueOf(facts.getOrDefault("recipientName", "")).trim();
        String why = String.valueOf(facts.getOrDefault("paymentPurpose", "")).trim();

        lines.add("You are about to pay " + rupees(facts.get("paymentAmountInr"))
                + (to.isEmpty() ? "" : " to " + to)
                + (why.isEmpty() ? "" : " for " + why) + ". The payment has not been made yet.");

        /* 1. The spending limits: the ones that are broken, else the tightest one, else all clear. */

        List<?> limits = (List<?>) facts.get("limits");

        boolean strained = false;
        int over = 0;

        for (Object o : limits) {
            Map<?, ?> m = (Map<?, ?>) o;

            if (m.containsKey("overLimitByInr") && over < 2) {
                BigDecimal times = (BigDecimal) m.get("paymentIsTimesTheLimit");

                lines.add("This payment goes OVER your " + m.get("period") + " limit of " + rupees(m.get("limitInr"))
                        + " by " + rupees(m.get("overLimitByInr")) + "."
                        + (over == 0 && times.compareTo(BigDecimal.valueOf(2)) >= 0
                                ? " The payment alone is about " + times.setScale(0, RoundingMode.HALF_UP).toPlainString()
                                        + " times that limit."
                                : ""));
                over++;
                strained = true;
            }
        }

        if (over == 0) {
            Map<?, ?> low = null;

            for (Object o : limits) {
                if (low == null && "low".equals(((Map<?, ?>) o).get("status"))) {
                    low = (Map<?, ?>) o;
                }
            }

            if (limits.isEmpty()) {
                lines.add("No spending limit is set, so the budget cannot be checked.");

            } else if (low != null) {
                lines.add("This payment fits your " + low.get("period") + " limit of " + rupees(low.get("limitInr"))
                        + " but leaves only " + rupees(low.get("leftAfterThisPaymentInr")) + " of it.");
                strained = true;

            } else {
                Map<?, ?> first = (Map<?, ?>) limits.get(0);

                lines.add("This payment fits every spending limit you set. After it, " + rupees(first.get("leftAfterThisPaymentInr"))
                        + " is left of your " + first.get("period") + " limit of " + rupees(first.get("limitInr")) + ".");
            }
        }

        /* 2. The most urgent EMI: one that is overdue, else the next one if it is due within a week. */

        Map<?, ?> overdue = null;
        Map<?, ?> next = null;

        for (Object o : (List<?>) facts.get("loans")) {
            Map<?, ?> m = (Map<?, ?>) o;

            if (m.containsKey("overdueByDays") && overdue == null) {
                overdue = m;
            }

            if (m.containsKey("dueInDays") && (next == null || (Long) m.get("dueInDays") < (Long) next.get("dueInDays"))) {
                next = m;
            }
        }

        if (overdue != null) {
            lines.add("Your EMI \"" + overdue.get("name") + "\" of " + rupees(overdue.get("emiInr"))
                    + " is overdue by " + days(overdue.get("overdueByDays")) + ".");
        }

        if (next != null && Boolean.TRUE.equals(facts.get("anEmiIsDueWithin7Days"))) {
            boolean today = Long.valueOf(0).equals(next.get("dueInDays"));

            lines.add("Your EMI \"" + next.get("name") + "\" of " + rupees(next.get("emiInr"))
                    + (today ? " is due today" : " is due in " + days(next.get("dueInDays")))
                    + (Boolean.TRUE.equals(facts.get("thisPaymentMayLeaveTooLittleForThatEmi"))
                            ? ", and after this payment this month's budget may not cover it."
                            : ", and this payment still leaves room for it this month."));
        }

        /* 3. Household bills waiting to be paid. */

        if (facts.get("householdBillsUnpaidNext7DaysInr") instanceof BigDecimal bills && bills.signum() > 0) {
            lines.add("Household bills of " + rupees(bills) + " are unpaid and due within a week.");
        }

        /* 4. A savings goal, only when this payment strains the budget. */

        if (strained) {
            for (Object o : (List<?>) facts.get("savingsGoals")) {
                Map<?, ?> m = (Map<?, ?>) o;

                if (((BigDecimal) m.get("stillNeededInr")).signum() > 0) {
                    lines.add("Your savings goal \"" + m.get("name") + "\" still needs " + rupees(m.get("stillNeededInr")) + ".");
                    break;
                }
            }
        }

        return lines;
    }

    /** "₹1,500" or "₹98.63": the way the app shows money, so the AI repeats it the same way. */
    private static String rupees(Object value) {
        BigDecimal amount = value instanceof BigDecimal b ? b : BigDecimal.ZERO;

        boolean whole = amount.stripTrailingZeros().scale() <= 0;

        return "₹" + new DecimalFormat(whole ? "#,##0" : "#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
                .format(amount);
    }

    private static String days(Object count) {
        return Long.valueOf(1).equals(count) ? "1 day" : count + " days";
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
