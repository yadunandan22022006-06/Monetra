package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Savings goal awareness: how far each goal is, how much must be saved a day, a week or a month
 * to reach it by its date, whether the user is behind their plan, and what a payment would do to it.
 *
 * A goal's saved amount only changes when the user records it. A payment never lowers it by itself.
 */
@Service
public class SavingsGoalService {

    /** remainingGoalAmount / remainingContributionPeriods, to the paisa. */
    public static BigDecimal requiredContribution(BigDecimal remaining, long periods) {
        return remaining.max(BigDecimal.ZERO).divide(BigDecimal.valueOf(Math.max(1, periods)), 2, RoundingMode.HALF_UP);
    }

    /** Every goal with its figures worked out for today. */
    public List<Map<String, Object>> goals(JsonNode data, LocalDate today) {
        List<Map<String, Object>> out = new ArrayList<>();

        if (data == null) {
            return out;
        }

        for (JsonNode n : data.path("goals")) {
            out.add(goal(n, today));
        }

        return out;
    }

    private Map<String, Object> goal(JsonNode n, LocalDate today) {
        BigDecimal target = BudgetRules.decimal(n.path("target"));
        BigDecimal saved = BudgetRules.decimal(n.path("saved"));

        /* remainingGoalAmount = targetAmount - eligibleCurrentGoalSavings */
        BigDecimal remaining = target.subtract(saved).max(BigDecimal.ZERO);

        BigDecimal planned = BudgetRules.decimal(n.path("monthly"));

        String priority = n.path("priority").asText("MEDIUM");

        Map<String, Object> g = new LinkedHashMap<>();
        g.put("id", n.path("id").asText(""));
        g.put("name", n.path("name").asText("Goal"));
        g.put("target", BudgetRules.money(target));
        g.put("saved", BudgetRules.money(saved));
        g.put("remaining", BudgetRules.money(remaining));
        g.put("percent", target.signum() > 0
                ? saved.multiply(BigDecimal.valueOf(100)).divide(target, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(100)).intValue()
                : 0);
        g.put("priority", "HIGH".equals(priority) || "LOW".equals(priority) ? priority : "MEDIUM");
        g.put("plannedMonthly", BudgetRules.money(planned));
        g.put("targetDate", "");

        boolean reached = target.signum() > 0 && remaining.signum() == 0;

        if (reached) {
            g.put("status", "REACHED");
            return g;
        }

        LocalDate date = date(n.path("targetDate"));

        if (date == null) {
            g.put("status", "NO_DATE");

        } else {
            g.put("targetDate", date.toString());

            if (!date.isAfter(today)) {
                /* The date has passed: the user has to pick a new one before a plan can be worked out. */
                g.put("status", "DATE_PASSED");

            } else {
                long days = ChronoUnit.DAYS.between(today, date);
                long months = monthsUp(today, date);

                g.put("daysLeft", days);
                g.put("monthsLeft", months);
                g.put("requiredMonthly", requiredContribution(remaining, months));
                g.put("requiredWeekly", requiredContribution(remaining, (days + 6) / 7));
                g.put("requiredDaily", requiredContribution(remaining, days));
                g.put("status", "ON_TRACK");

                /* Behind the plan? One planned contribution is expected for every full month since the goal was made. */

                LocalDate created = date(n.path("createdDate"));

                if (created != null && created.isBefore(date)) {
                    BigDecimal start = n.has("startSaved") ? BudgetRules.decimal(n.path("startSaved")) : saved;

                    BigDecimal perMonth = planned.signum() > 0
                            ? planned
                            : requiredContribution(target.subtract(start), monthsUp(created, date));

                    long fullMonths = Math.max(0, ChronoUnit.MONTHS.between(created, today));

                    BigDecimal shouldHave = start.add(perMonth.multiply(BigDecimal.valueOf(fullMonths))).min(target);
                    BigDecimal shortfall = shouldHave.subtract(saved);

                    g.put("shouldHaveSaved", BudgetRules.money(shouldHave));

                    if (shortfall.signum() > 0) {
                        g.put("shortfall", BudgetRules.money(shortfall));
                        g.put("status", "BEHIND");
                    }
                }
            }
        }

        /* When would the goal be reached at the pace the user planned (or the pace the date needs)? */

        BigDecimal pace = planned.signum() > 0 ? planned : (BigDecimal) g.get("requiredMonthly");

        if (pace != null && pace.signum() > 0) {
            long monthsNeeded = remaining.divide(pace, 0, RoundingMode.CEILING).longValue();

            g.put("monthlyPace", pace);
            g.put("estimatedCompletion", today.plusMonths(Math.min(monthsNeeded, 1200)).toString());
        }

        return g;
    }

    /** The goal a payment is most likely to touch: unfinished, highest priority, then the nearest date. */
    public Map<String, Object> active(List<Map<String, Object>> goals) {
        return goals.stream()
                .filter(g -> !"REACHED".equals(g.get("status")) && ((BigDecimal) g.get("target")).signum() > 0)
                .min(Comparator
                        .comparing((Map<String, Object> g) -> rank(String.valueOf(g.get("priority"))))
                        .thenComparing(g -> String.valueOf(g.get("targetDate")).isEmpty() ? "9999" : String.valueOf(g.get("targetDate"))))
                .orElse(null);
    }

    /**
     * What would happen to a goal IF a payment came out of the money saved for it. Nothing is changed:
     * the goal's saved amount only moves when the user records it.
     */
    public Map<String, Object> impact(Map<String, Object> goal, BigDecimal payment) {
        BigDecimal target = (BigDecimal) goal.get("target");
        BigDecimal saved = (BigDecimal) goal.get("saved");

        BigDecimal taken = payment.min(saved);
        BigDecimal savedAfter = saved.subtract(taken);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("savedAfter", BudgetRules.money(savedAfter));
        out.put("remainingBefore", goal.get("remaining"));
        out.put("remainingAfter", BudgetRules.money(target.subtract(savedAfter)));

        BigDecimal pace = (BigDecimal) goal.get("monthlyPace");

        /* A delay can only be estimated when a monthly pace is known, and only when it comes to a month or more. */
        if (pace != null && pace.signum() > 0) {
            long months = taken.divide(pace, 0, RoundingMode.HALF_UP).longValue();

            if (months >= 1) {
                out.put("delayMonths", months);
            }
        }

        return out;
    }

    /** Whole months from one date to another, counting a part month as one. At least 1. */
    static long monthsUp(LocalDate from, LocalDate to) {
        long months = ChronoUnit.MONTHS.between(from, to);

        if (from.plusMonths(months).isBefore(to)) {
            months++;
        }

        return Math.max(1, months);
    }

    private static LocalDate date(JsonNode node) {
        try {
            return LocalDate.parse(node.asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    private static int rank(String priority) {
        return "HIGH".equals(priority) ? 0 : "LOW".equals(priority) ? 2 : 1;
    }
}
