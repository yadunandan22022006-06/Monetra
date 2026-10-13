package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Daily spending awareness: how much of today's limit is used, what is left, and what a
 * payment would do to it and to the weekly, monthly and yearly limits.
 */
@Service
public class DailyBudgetService {

    /**
     * @param amount the payment being considered, or zero to look at today as it stands
     * @return limit, spentToday, remaining, percentUsed, overBy, status (LOW, MEDIUM, HIGH or UNKNOWN)
     *         and every limit the payment would break
     */
    public Map<String, Object> assess(JsonNode data, BigDecimal amount, LocalDate today) {
        Map<String, Object> check = BudgetRules.check(data, amount, today);

        BigDecimal limit = (BigDecimal) check.get("dailyLimit");
        BigDecimal spent = (BigDecimal) check.get("spentToday");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("limitSet", limit.signum() > 0);
        out.put("limit", limit);
        out.put("spentToday", spent);

        if (limit.signum() > 0) {
            /* remainingDailyBudget = dailySpendingLimit - eligibleSpendingToday */
            BigDecimal remaining = limit.subtract(spent);

            out.put("remaining", remaining.max(BigDecimal.ZERO));
            out.put("percentUsed", spent.multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.HALF_UP).intValue());

            if (amount.compareTo(remaining) > 0) {
                out.put("overBy", BudgetRules.money(amount.subtract(remaining)));
            }
        }

        /* Every limit this payment would break, shortest period first, and the first one it only strains. */

        List<Map<String, Object>> broken = new ArrayList<>();
        Map<String, Object> tight = null;

        for (Object o : (List<?>) check.get("periods")) {
            Map<?, ?> p = (Map<?, ?>) o;

            BigDecimal after = (BigDecimal) p.get("remainingAfter");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", p.get("period"));
            row.put("limit", p.get("limit"));
            row.put("spent", p.get("spent"));

            if ("over".equals(p.get("level"))) {
                row.put("overBy", after.negate());
                broken.add(row);

            } else if ("low".equals(p.get("level")) && tight == null) {
                row.put("left", after);
                tight = row;
            }
        }

        out.put("broken", broken);
        out.put("tight", tight);

        String level = String.valueOf(check.get("level"));

        out.put("status", "over".equals(level) ? "HIGH" : "low".equals(level) ? "MEDIUM" : "ok".equals(level) ? "LOW" : "UNKNOWN");

        return out;
    }
}
