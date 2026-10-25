package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * Checks the parts of a user's records that the Guardian calculates with: loans, savings goals,
 * the money profile, the spending limits and the amounts of expenses, income and savings.
 * Amounts must be real, not negative and not absurd; dates must be dates.
 */
public final class RecordsValidator {

    private static final BigDecimal MAX = new BigDecimal("1000000000");
    private static final int MAX_ITEMS = 200;

    /** Expenses and the like can be many; this only stops an absurdly long list. */
    private static final int MAX_ENTRIES = 5000;

    private static final Set<String> FREQUENCIES = Set.of("MONTHLY", "WEEKLY", "YEARLY");
    private static final Set<String> LOAN_STATUSES = Set.of("ACTIVE", "CLOSED");
    private static final Set<String> PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private RecordsValidator() {
    }

    /** A message for the user when something is wrong, or null when the records are fine. */
    public static String problem(JsonNode records) {
        /* The lists must be lists and the groups must be groups (the page always sends them so). */
        for (String list : new String[] {"loans", "goals", "expenses", "income", "savings", "payments"}) {
            JsonNode node = records.path(list);

            if (!node.isMissingNode() && !node.isNull() && !node.isArray()) {
                return "Your records could not be saved: the " + list + " part is not in the expected format.";
            }
        }

        for (String group : new String[] {"limits", "profile"}) {
            JsonNode node = records.path(group);

            if (!node.isMissingNode() && !node.isNull() && !node.isObject()) {
                return "Your records could not be saved: the " + group + " part is not in the expected format.";
            }
        }

        JsonNode loans = records.path("loans");

        if (loans.size() > MAX_ITEMS || records.path("goals").size() > MAX_ITEMS) {
            return "There are too many loans or goals.";
        }

        for (JsonNode loan : loans) {
            if (!text(loan.path("name"), 60) || loan.path("name").asText("").isBlank()) {
                return "Every loan needs a name of up to 60 characters.";
            }

            if (!amount(loan.path("amount")) || loan.path("amount").asDouble(0) <= 0) {
                return "Enter a valid EMI amount for each loan.";
            }

            if (!amount(loan.path("original")) || !amount(loan.path("outstanding"))) {
                return "Enter valid loan amounts.";
            }

            if (!amount(loan.path("rate")) || loan.path("rate").asDouble(0) > 100) {
                return "The interest rate must be between 0 and 100.";
            }

            if (!count(loan.path("installmentsLeft"), 1200)) {
                return "The number of installments left must be between 0 and 1200.";
            }

            if (!date(loan.path("due"))) {
                return "Enter a valid next EMI date for each loan.";
            }

            if (!oneOf(loan.path("frequency"), FREQUENCIES) || !oneOf(loan.path("status"), LOAN_STATUSES)) {
                return "A loan has an unknown payment frequency or status.";
            }
        }

        for (JsonNode goal : records.path("goals")) {
            if (!text(goal.path("name"), 60) || goal.path("name").asText("").isBlank()) {
                return "Every goal needs a name of up to 60 characters.";
            }

            if (!amount(goal.path("target")) || goal.path("target").asDouble(0) <= 0) {
                return "Enter a valid target amount for each goal.";
            }

            if (!amount(goal.path("saved")) || !amount(goal.path("monthly")) || !amount(goal.path("startSaved"))) {
                return "Enter valid savings amounts for each goal.";
            }

            if (!date(goal.path("targetDate")) || !date(goal.path("createdDate"))) {
                return "Enter a valid target date for each goal.";
            }

            if (!oneOf(goal.path("priority"), PRIORITIES) || !count(goal.path("remindDay"), 28)) {
                return "A goal has an unknown priority or reminder day.";
            }
        }

        /* Every spending limit is a real amount from 0 up, and so is every amount in the lists of entries. */

        JsonNode limits = records.path("limits");

        for (String period : new String[] {"daily", "weekly", "monthly", "yearly"}) {
            if (!amount(limits.path(period))) {
                return "Enter a valid " + period + " spending limit: an amount from 0 to " + MAX.toPlainString() + ".";
            }
        }

        if (!amount(records.path("dailyLimit"))) {
            return "Enter a valid daily spending limit.";
        }

        for (String list : new String[] {"expenses", "income", "savings", "payments"}) {
            JsonNode entries = records.path(list);

            if (entries.size() > MAX_ENTRIES) {
                return "There are too many " + list + ".";
            }

            for (JsonNode entry : entries) {
                if (!amount(entry.path("amount"))) {
                    return "Every amount in your " + list + " must be a number from 0 to " + MAX.toPlainString() + ".";
                }
            }
        }

        JsonNode profile = records.path("profile");

        if (!amount(profile.path("monthlyIncome")) || !amount(profile.path("essentials")) || !amount(profile.path("buffer"))) {
            return "Enter valid amounts for your income, essential expenses and buffer.";
        }

        if (!profile.path("balance").isMissingNode() && !profile.path("balance").isNull() && !amount(profile.path("balance"))) {
            return "Enter a valid available balance.";
        }

        if (!count(profile.path("payday"), 31)) {
            return "The income day must be a day of the month from 1 to 31.";
        }

        return null;
    }

    /** Missing is fine. Otherwise it must be a number from 0 to the maximum. */
    private static boolean amount(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || (node.isTextual() && node.asText().isBlank())) {
            return true;
        }

        try {
            BigDecimal value = new BigDecimal(node.asText());

            return value.signum() >= 0 && value.compareTo(MAX) <= 0;

        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean count(JsonNode node, int max) {
        if (node.isMissingNode() || node.isNull() || (node.isTextual() && node.asText().isBlank())) {
            return true;
        }

        return node.canConvertToInt() && node.asInt() >= 0 && node.asInt() <= max;
    }

    private static boolean date(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || node.asText("").isBlank()) {
            return true;
        }

        try {
            LocalDate.parse(node.asText());
            return true;

        } catch (Exception e) {
            return false;
        }
    }

    private static boolean text(JsonNode node, int max) {
        return node.isMissingNode() || node.isNull() || node.asText("").length() <= max;
    }

    private static boolean oneOf(JsonNode node, Set<String> allowed) {
        return node.isMissingNode() || node.isNull() || node.asText("").isBlank() || allowed.contains(node.asText());
    }
}
