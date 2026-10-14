package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Smart Buy Planner: "When can I buy it?"
 *
 * Follows the user's money day by day for the next twelve months: income on each income day, every
 * EMI and household bill on its due date, and essential expenses and goal savings spread over each
 * month. The earliest safe day to buy is the first day from which the purchase never takes the money
 * below the user's safety buffer, so every EMI, bill and goal saving after it is still paid.
 *
 * Every figure comes from the user's saved records. Nothing is bought, saved or moved.
 */
@Service
public class PurchasePlannerService {

    /** How far ahead the plan looks, in days. */
    static final int DAYS = 365;

    private static final BigDecimal THIRTY = BigDecimal.valueOf(30);

    private final HomeExpenseService home;
    private final LoanRepaymentService loanRepayment;
    private final SavingsGoalService savingsGoals;
    private final FinancialForecastService forecasts;

    public PurchasePlannerService(
            HomeExpenseService home,
            LoanRepaymentService loanRepayment,
            SavingsGoalService savingsGoals,
            FinancialForecastService forecasts) {

        this.home = home;
        this.loanRepayment = loanRepayment;
        this.savingsGoals = savingsGoals;
        this.forecasts = forecasts;
    }

    /**
     * @param wanted the day the user would like to buy it, or null
     * @param saving how much the user can put aside for it each month, or null
     */
    public Map<String, Object> plan(
            Long userId, JsonNode data, String item, BigDecimal price, LocalDate wanted, BigDecimal saving, LocalDate today) {
        JsonNode profile = data.path("profile");

        BigDecimal balance = forecasts.availableBalance(data);
        BigDecimal income = BudgetRules.decimal(profile.path("monthlyIncome"));
        BigDecimal essentials = BudgetRules.decimal(profile.path("essentials"));
        BigDecimal buffer = BudgetRules.decimal(profile.path("buffer"));
        LocalDate payday = forecasts.nextPayday(data, today);

        String name = item == null || item.isBlank() ? "it" : "\"" + item.trim() + "\"";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", item == null ? "" : item.trim());
        out.put("price", BudgetRules.money(price));

        /* Without a balance, or with an income but no income day, there is nothing safe to work from. */

        List<String> missing = new ArrayList<>();

        if (balance == null) {
            missing.add("your available balance");
        }

        if (income.signum() > 0 && payday == null) {
            missing.add("the day your income arrives");
        }

        if (balance == null || !missing.isEmpty()) {
            out.put("ready", false);
            out.put("missing", missing);
            out.put("lines", List.of("Monetra needs " + String.join(" and ", missing) + " to plan this. Add "
                    + (missing.size() == 1 ? "it" : "them") + " in Spending, under My money."));
            return out;
        }

        LocalDate end = today.plusDays(DAYS);

        BigDecimal[] change = new BigDecimal[DAYS + 1];

        Arrays.fill(change, BigDecimal.ZERO);

        /* Income on each income day. */

        if (income.signum() > 0 && payday != null) {
            int day = profile.path("payday").asInt();

            for (LocalDate p = payday; !p.isAfter(end); ) {
                change[index(today, p)] = change[index(today, p)].add(income);

                YearMonth next = YearMonth.from(p).plusMonths(1);

                p = next.atDay(Math.min(day, next.lengthOfMonth()));
            }
        }

        /* Every EMI and household bill on its date. One that is already overdue has to be paid now. */

        for (LoanRepaymentService.Installment emi : loanRepayment.schedule(loanRepayment.loans(data), today, end)) {
            int i = index(today, emi.date());

            change[i] = change[i].subtract(emi.loan().emi());
        }

        BigDecimal billsInYear = BigDecimal.ZERO;

        for (HomeExpenseService.Row bill : home.actionable(userId, today, DAYS)) {
            int i = index(today, bill.due());

            change[i] = change[i].subtract(bill.expense().amount);

            billsInYear = billsInYear.add(bill.expense().amount);
        }

        /* Essential expenses: a month's worth spread over every 30 days. */

        BigDecimal essentialsDaily = essentials.divide(THIRTY, 4, RoundingMode.HALF_UP);

        for (int i = 0; i <= DAYS; i++) {
            change[i] = change[i].subtract(essentialsDaily);
        }

        /* Goal savings: each unfinished goal keeps its monthly saving until it is reached or its date comes. */

        BigDecimal goalsMonthly = BigDecimal.ZERO;
        List<String> goalNames = new ArrayList<>();

        for (Map<String, Object> goal : savingsGoals.goals(data, today)) {
            if ("REACHED".equals(goal.get("status"))) {
                continue;
            }

            BigDecimal monthly = (BigDecimal) goal.get("plannedMonthly");

            if (monthly.signum() <= 0 && goal.get("requiredMonthly") instanceof BigDecimal required) {
                monthly = required;
            }

            if (monthly.signum() <= 0) {
                continue;
            }

            LocalDate until = end;

            if (("ON_TRACK".equals(goal.get("status")) || "BEHIND".equals(goal.get("status")))
                    && !String.valueOf(goal.get("targetDate")).isEmpty()) {
                until = LocalDate.parse(String.valueOf(goal.get("targetDate")));
            }

            BigDecimal daily = monthly.divide(THIRTY, 4, RoundingMode.HALF_UP);
            BigDecimal left = (BigDecimal) goal.get("remaining");

            for (int i = 0; i <= DAYS && left.signum() > 0 && !today.plusDays(i).isAfter(until); i++) {
                BigDecimal take = daily.min(left);

                change[i] = change[i].subtract(take);

                left = left.subtract(take);
            }

            goalsMonthly = goalsMonthly.add(monthly);
            goalNames.add("\"" + goal.get("name") + "\"");
        }

        /* The money on each day, and the lowest it gets from each day on. */

        BigDecimal[] cash = new BigDecimal[DAYS + 1];
        BigDecimal running = balance;

        for (int i = 0; i <= DAYS; i++) {
            running = running.add(change[i]);
            cash[i] = running;
        }

        BigDecimal[] lowest = new BigDecimal[DAYS + 1];
        int[] lowestDay = new int[DAYS + 1];

        lowest[DAYS] = cash[DAYS];
        lowestDay[DAYS] = DAYS;

        for (int i = DAYS - 1; i >= 0; i--) {
            if (cash[i].compareTo(lowest[i + 1]) <= 0) {
                lowest[i] = cash[i];
                lowestDay[i] = i;

            } else {
                lowest[i] = lowest[i + 1];
                lowestDay[i] = lowestDay[i + 1];
            }
        }

        /* The first day the purchase still leaves the buffer untouched on every day after it. */

        int safe = -1;

        for (int i = 0; i <= DAYS; i++) {
            if (lowest[i].subtract(buffer).compareTo(price) >= 0) {
                safe = i;
                break;
            }
        }

        BigDecimal freeNow = BudgetRules.money(lowest[0].subtract(buffer).max(BigDecimal.ZERO));

        BigDecimal emisMonthly = loanRepayment.monthlyEmi(loanRepayment.loans(data));
        BigDecimal billsMonthly = billsInYear.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);

        BigDecimal freeMonthly = income.signum() > 0
                ? BudgetRules.money(income.subtract(emisMonthly).subtract(essentials).subtract(billsMonthly).subtract(goalsMonthly))
                : null;

        List<String> lines = new ArrayList<>();

        lines.add("You want to buy " + name + " for " + Inr.of(price) + ".");

        out.put("ready", true);
        out.put("buyToday", safe == 0);
        out.put("freeNow", freeNow);
        out.put("freeMonthly", freeMonthly);
        out.put("safeDate", safe >= 0 ? today.plusDays(safe).toString() : null);

        if (safe == 0) {
            BigDecimal after = BudgetRules.money(lowest[0].subtract(price));

            lines.add("You can buy it today. Your money would not go below " + Inr.of(after) + " in the next 12 months, "
                    + "and every EMI, household bill and goal saving is still paid.");

            out.put("recommendation", "You can buy it without touching your EMIs, bills or goal savings.");
            out.put("estimatedDate", today.toString());
            out.put("daysToWait", 0);
            out.put("lowestAfter", after);

            out.put("lines", finish(lines, goalsMonthly, goalNames, essentials));
            return out;
        }

        /* Why not today: how low the money would go, and when. */

        BigDecimal low = BudgetRules.money(lowest[0].subtract(price));
        LocalDate lowDate = today.plusDays(lowestDay[0]);

        String warning = "Buying it today is NOT safe: your money would fall to " + Inr.of(low) + " on " + Inr.date(lowDate)
                + (low.signum() < 0
                        ? ", which is NOT enough for your EMIs, household bills, essential expenses and goal savings."
                        : ", below the " + Inr.of(buffer) + " you want to keep for emergencies.");

        lines.add(warning);

        out.put("warning", warning);
        out.put("recommendation", "Protect your upcoming EMIs and essential bills first, and save for it until it is safe.");
        out.put("lowestIfToday", low);
        out.put("lowestIfTodayDate", lowDate.toString());

        BigDecimal needed = price.subtract(freeNow);

        /* The day the plan aims for: the earliest safe day, or a later one the user chose. Never earlier. */

        LocalDate aim = safe > 0 ? today.plusDays(safe) : null;

        if (aim != null) {
            lines.add("The earliest safe day to buy it is " + Inr.date(aim) + ", in " + safe + (safe == 1 ? " day." : " days."));
        }

        if (wanted != null) {
            boolean wantedSafe = lowest[index(today, wanted)].subtract(buffer).compareTo(price) >= 0;

            out.put("wantedDate", wanted.toString());
            out.put("wantedSafe", wantedSafe);

            if (wantedSafe) {
                lines.add("Buying it on " + Inr.date(wanted) + ", the day you want, is safe.");

                aim = wanted;

            } else {
                lines.add("Buying it on " + Inr.date(wanted) + ", the day you want, is NOT safe"
                        + (aim != null ? ". The earliest safe day is " + Inr.date(aim) + "." : "."));
            }
        }

        /* With the saving the user says they can manage, when would the money be there? */

        if (saving != null && saving.signum() > 0) {
            long days = needed.divide(saving.divide(THIRTY, 4, RoundingMode.HALF_UP), 0, RoundingMode.CEILING).longValue();

            LocalDate bySaving = today.plusDays(days);

            out.put("savingDate", bySaving.toString());

            if (aim == null) {
                lines.add("Putting aside " + Inr.of(saving) + " a month, it would take about " + days + " days.");

            } else if (bySaving.isBefore(aim)) {
                lines.add("Putting aside " + Inr.of(saving) + " a month would be enough by " + Inr.date(bySaving)
                        + ", but your EMIs and bills make " + Inr.date(aim) + " the first safe day.");

            } else {
                lines.add("Putting aside " + Inr.of(saving) + " a month, you can buy it on " + Inr.date(bySaving) + ".");

                aim = bySaving;
            }
        }

        if (aim != null) {
            long days = ChronoUnit.DAYS.between(today, aim);

            BigDecimal perDay = needed.divide(BigDecimal.valueOf(days), 0, RoundingMode.CEILING);

            /* A week's or a month's figure only when the wait is that long: ten days never need "a month". */

            lines.add("To buy it on " + Inr.date(aim) + ", put aside " + Inr.of(perDay) + " a day"
                    + (days >= 30 ? " (" + Inr.of(perDay.multiply(BigDecimal.valueOf(7))) + " a week, or "
                            + Inr.of(perDay.multiply(THIRTY)) + " a month)"
                            : days >= 7 ? " (" + Inr.of(perDay.multiply(BigDecimal.valueOf(7))) + " a week)" : "")
                    + ", " + Inr.of(needed) + " in all.");

            out.put("needed", BudgetRules.money(needed));
            out.put("estimatedDate", aim.toString());
            out.put("daysToWait", days);
            out.put("perDay", BudgetRules.money(perDay));
            out.put("perWeek", BudgetRules.money(perDay.multiply(BigDecimal.valueOf(7))));
            out.put("perMonth", BudgetRules.money(perDay.multiply(THIRTY)));

        } else if (saving == null && freeMonthly != null && freeMonthly.signum() > 0) {
            long months = needed.divide(freeMonthly, 0, RoundingMode.CEILING).longValue();

            lines.add("There is no safe day in the next 12 months. With about " + Inr.of(freeMonthly)
                    + " free each month, it would take about " + months + " months.");

            out.put("estimateMonths", months);

        } else if (saving == null && freeMonthly != null) {
            lines.add("There is no safe day in the next 12 months: your EMIs, household bills, essential expenses "
                    + "and goal savings use all of your income.");

        } else if (saving == null) {
            lines.add("There is no safe day in the next 12 months with the money you have now. "
                    + "Add your monthly income in Spending to see when you could buy it.");
        }

        out.put("lines", finish(lines, goalsMonthly, goalNames, essentials));
        return out;
    }

    /** What every plan keeps paid, and what it assumes. */
    private static List<String> finish(List<String> lines, BigDecimal goalsMonthly, List<String> goalNames, BigDecimal essentials) {
        if (goalsMonthly.signum() > 0) {
            lines.add("This plan keeps your goal savings of " + Inr.of(goalsMonthly) + " a month going ("
                    + String.join(", ", goalNames) + ").");
        }

        lines.add(essentials.signum() > 0
                ? "It assumes you spend only your essential expenses of " + Inr.of(essentials) + " a month, your EMIs and your bills until then."
                : "It assumes you spend only on your EMIs and bills until then. Add your essential expenses in Spending for a closer plan.");

        return lines;
    }

    /** The day's place in the plan. A date that has passed counts as today. */
    private static int index(LocalDate today, LocalDate date) {
        return (int) Math.max(0, Math.min(DAYS, ChronoUnit.DAYS.between(today, date)));
    }
}
