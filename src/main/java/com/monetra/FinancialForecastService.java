package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forecasts built from what the user told Monetra: the money they have now, the day their income
 * arrives, and next month's income against next month's known commitments. Nothing is invented:
 * when a figure is missing the forecast says it is incomplete.
 */
@Service
public class FinancialForecastService {

    /**
     * The balance the user typed in, moved by every expense and income recorded after they typed it.
     * Null when the user never gave a balance.
     */
    public BigDecimal availableBalance(JsonNode data) {
        if (data == null) {
            return null;
        }

        JsonNode profile = data.get("profile");

        if (profile == null || !profile.path("balance").isNumber()) {
            return null;
        }

        BigDecimal balance = profile.path("balance").decimalValue();

        long since = profile.path("balanceAt").asLong(0);

        if (since > 0) {
            for (JsonNode e : data.path("expenses")) {
                if (e.path("id").asLong(0) > since) {
                    balance = balance.subtract(BudgetRules.decimal(e.path("amount")));
                }
            }

            for (JsonNode i : data.path("income")) {
                if (i.path("id").asLong(0) > since) {
                    balance = balance.add(BudgetRules.decimal(i.path("amount")));
                }
            }
        }

        return BudgetRules.money(balance);
    }

    /** The next day income arrives, strictly after today. Null when the user has not set an income day. */
    public LocalDate nextPayday(JsonNode data, LocalDate today) {
        int day = data == null ? 0 : data.path("profile").path("payday").asInt(0);

        if (day < 1 || day > 31) {
            return null;
        }

        LocalDate thisMonth = today.withDayOfMonth(Math.min(day, today.lengthOfMonth()));

        if (thisMonth.isAfter(today)) {
            return thisMonth;
        }

        LocalDate next = today.plusMonths(1);

        return next.withDayOfMonth(Math.min(day, next.lengthOfMonth()));
    }

    /**
     * Next month: expected income minus EMIs, essential expenses, planned savings and scheduled bills,
     * each counted once. With a purchase, the same sum with the purchase taken off as well.
     *
     * @param expectedIncome null or zero when the user has not given a monthly income
     * @param purchase       null when no extra purchase is being considered
     */
    public Map<String, Object> nextMonth(
            BigDecimal expectedIncome,
            BigDecimal emis,
            BigDecimal essentials,
            BigDecimal savingsContributions,
            BigDecimal bills,
            BigDecimal purchase) {

        Map<String, Object> out = new LinkedHashMap<>();

        boolean known = expectedIncome != null && expectedIncome.signum() > 0;

        out.put("known", known);
        out.put("emis", BudgetRules.money(emis));
        out.put("essentials", BudgetRules.money(essentials));
        out.put("savingsContributions", BudgetRules.money(savingsContributions));
        out.put("bills", BudgetRules.money(bills));

        if (expectedIncome == null || !known) {
            /* Without an income the forecast is incomplete: say so instead of guessing one. */
            return out;
        }

        BigDecimal remaining = expectedIncome.subtract(emis).subtract(essentials).subtract(savingsContributions).subtract(bills);

        out.put("expectedIncome", BudgetRules.money(expectedIncome));
        out.put("projectedRemaining", BudgetRules.money(remaining));

        if (purchase != null && purchase.signum() > 0) {
            out.put("purchase", BudgetRules.money(purchase));
            out.put("projectedWithPurchase", BudgetRules.money(remaining.subtract(purchase)));
        }

        return out;
    }
}
