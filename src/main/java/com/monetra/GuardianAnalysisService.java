package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Monetra Financial Guardian. One analysis that looks at a payment three ways at once: today's
 * spending limits, the loans and bills that still have to be paid, and the savings goals.
 *
 * Every figure is worked out here from the user's saved records. The AI is only ever given the
 * finished sentences to put into friendlier words. The user always decides.
 */
@Service
public class GuardianAnalysisService {

    private static final int DEFAULT_HORIZON_DAYS = 30;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final UserDataRepository store;
    private final HomeExpenseService home;
    private final DailyBudgetService dailyBudget;
    private final LoanRepaymentService loanRepayment;
    private final SavingsGoalService savingsGoals;
    private final FinancialForecastService forecasts;
    private final GuardianAlertRepository alerts;
    private final NotificationRepository notifications;

    private final ObjectMapper mapper = new ObjectMapper();

    public GuardianAnalysisService(
            UserDataRepository store,
            HomeExpenseService home,
            DailyBudgetService dailyBudget,
            LoanRepaymentService loanRepayment,
            SavingsGoalService savingsGoals,
            FinancialForecastService forecasts,
            GuardianAlertRepository alerts,
            NotificationRepository notifications) {

        this.store = store;
        this.home = home;
        this.dailyBudget = dailyBudget;
        this.loanRepayment = loanRepayment;
        this.savingsGoals = savingsGoals;
        this.forecasts = forecasts;
        this.alerts = alerts;
        this.notifications = notifications;
    }

    /** Where the user's money stands between today and the day their next income arrives. */
    private record Position(
            LocalDate payday,
            LocalDate horizon,
            long days,
            BigDecimal balance,
            BigDecimal emisDue,
            String nextEmi,
            BigDecimal bills,
            String nextBill,
            BigDecimal essentials,
            BigDecimal essentialsMonthly,
            BigDecimal buffer,
            BigDecimal monthlyIncome,
            List<LoanRepaymentService.Loan> loans) {

        boolean hasCommitments() {
            return emisDue.signum() > 0 || bills.signum() > 0 || essentials.signum() > 0;
        }

        /** "before your next income on 1 Nov 2026", or "in the next 30 days" when the income day is not known. */
        String until() {
            return payday != null ? "before your next income on " + Inr.date(payday) : "in the next " + days + " days";
        }

        /** "your EMIs of ₹6,000.00 and essential expenses of ₹5,000.00". */
        String commitments() {
            List<String> parts = new ArrayList<>();

            if (emisDue.signum() > 0) {
                parts.add("EMIs of " + Inr.of(emisDue));
            }

            if (bills.signum() > 0) {
                parts.add("household bills of " + Inr.of(bills) + (nextBill.isEmpty() ? "" : " (" + nextBill + ")"));
            }

            if (essentials.signum() > 0) {
                parts.add("essential expenses of " + Inr.of(essentials));
            }

            if (parts.isEmpty()) {
                return "";
            }

            String last = parts.remove(parts.size() - 1);

            return "your " + (parts.isEmpty() ? last : String.join(", ", parts) + " and " + last);
        }
    }

    /** The user's saved records. Never null. */
    public JsonNode data(Long userId) {
        try {
            UserData row = store.findById(userId).orElse(null);

            return mapper.readTree(row == null ? "{}" : row.getJson());

        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }

    private Position position(Long userId, JsonNode data, LocalDate today) {
        JsonNode profile = data.path("profile");

        LocalDate payday = forecasts.nextPayday(data, today);
        LocalDate horizon = payday != null ? payday : today.plusDays(DEFAULT_HORIZON_DAYS);

        long days = LoanRepaymentService.daysUntil(today, horizon);

        List<LoanRepaymentService.Loan> loans = loanRepayment.loans(data);

        BigDecimal bills = BigDecimal.ZERO;
        String nextBill = "";

        for (HomeExpenseService.Row r : home.actionable(userId, today, (int) days)) {
            bills = bills.add(r.expense().amount);

            if (nextBill.isEmpty()) {
                nextBill = "\"" + r.expense().name + "\" due " + Inr.date(r.due());
            }
        }

        BigDecimal essentialsMonthly = BudgetRules.decimal(profile.path("essentials"));

        /* The share of a month's essential expenses that falls before the next income. */
        BigDecimal essentials = essentialsMonthly.multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);

        List<LoanRepaymentService.Installment> installments = loanRepayment.schedule(loans, today, horizon);

        String nextEmi = "";

        if (!installments.isEmpty()) {
            LoanRepaymentService.Installment first = installments.get(0);

            /* Its amount is already in the EMI total, so only the name and the date are added. */
            nextEmi = "\"" + first.loan().name() + "\""
                    + (first.overdue() ? ", overdue since " : first.date().equals(today) ? ", due today, " : ", due on ")
                    + Inr.date(first.date());
        }

        return new Position(
                payday,
                horizon,
                days,
                forecasts.availableBalance(data),
                loanRepayment.total(installments),
                nextEmi,
                BudgetRules.money(bills),
                nextBill,
                essentials,
                essentialsMonthly,
                BudgetRules.decimal(profile.path("buffer")),
                BudgetRules.decimal(profile.path("monthlyIncome")),
                loans);
    }

    /* ------------------------------------------------------------------ */
    /* Before a payment                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * The Guardian's review of one proposed payment: the risk in each area, the warning cards that
     * apply, plain notes for what is fine, possible alternatives and what could not be checked.
     */
    public Map<String, Object> review(Long userId, JsonNode data, BigDecimal amount, String recipient, LocalDate today) {
        Position pos = position(userId, data, today);

        Map<String, Object> daily = dailyBudget.assess(data, amount, today);

        List<Map<String, String>> cards = new ArrayList<>();

        /* What is fine or could not be checked, with the area it belongs to. */
        List<Map<String, String>> notes = new ArrayList<>();
        List<String> options = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<Map<String, String>> impacts = new ArrayList<>();

        /* 1. Spending limits. */

        String dailyStatus = String.valueOf(daily.get("status"));

        List<?> broken = (List<?>) daily.get("broken");

        if (!broken.isEmpty()) {
            Map<?, ?> first = (Map<?, ?>) broken.get(0);

            String period = String.valueOf(first.get("period"));
            BigDecimal limit = (BigDecimal) first.get("limit");
            BigDecimal spent = (BigDecimal) first.get("spent");

            StringBuilder text = new StringBuilder("Your " + Inr.of(limit) + " " + period + " limit is exceeded by "
                    + Inr.of((BigDecimal) first.get("overBy")) + ".");

            for (int i = 1; i < broken.size(); i++) {
                Map<?, ?> other = (Map<?, ?>) broken.get(i);

                text.append(" Your ").append(Inr.of((BigDecimal) other.get("limit"))).append(" ").append(other.get("period"))
                        .append(" limit is also exceeded by ").append(Inr.of((BigDecimal) other.get("overBy"))).append(".");
            }

            Map<String, String> limitCard = card(period.toUpperCase() + " LIMIT EXCEEDED", text.toString());

            cards.add(limitCard);

            String who = Inr.of(amount) + (recipient == null || recipient.isBlank() ? "" : " to " + recipient.trim());

            BigDecimal ratio = amount.divide(limit, 1, RoundingMode.HALF_UP);

            if (ratio.compareTo(new BigDecimal("1.1")) >= 0) {
                cards.add(card("PAYMENT RISK", who + " is approximately " + times(ratio) + " times your " + period + " limit."));

            } else if (ratio.compareTo(BigDecimal.ONE) >= 0) {
                cards.add(card("PAYMENT RISK", who + " is about as much as your whole " + period + " limit of " + Inr.of(limit) + "."));

            } else {
                cards.add(card("PAYMENT RISK", who + " uses " + amount.multiply(HUNDRED).divide(limit, 0, RoundingMode.HALF_UP)
                        + "% of your " + period + " limit, and " + Inr.of(spent) + " of it was already spent."));
            }

            BigDecimal room = limit.subtract(spent);

            String option = room.signum() > 0
                    ? "Pay " + Inr.of(room) + " or less to stay inside your " + period + " limit."
                    : "daily".equals(period)
                            ? "Wait until tomorrow, when your daily limit starts again."
                            : "Review your " + period + " limit in Spending before paying.";

            options.add(option);

            /* What to do goes in the box it belongs to, not in a separate list that repeats it. */
            limitCard.put("action", option);
        }

        if (Boolean.TRUE.equals(daily.get("limitSet")) || !"UNKNOWN".equals(dailyStatus)) {
            impacts.add(impact("Daily budget", dailyStatus));
        }

        /* 2. Loans, bills and essential expenses that still have to be paid. */

        String loanStatus = "UNKNOWN";

        if (pos.balance() != null) {
            Map<String, Object> a = loanRepayment.assess(
                    pos.balance(), BigDecimal.ZERO, amount, pos.emisDue(), pos.essentials(), pos.bills(), pos.buffer());

            BigDecimal projected = (BigDecimal) a.get("projectedBalance");

            loanStatus = String.valueOf(a.get("status"));

            String title = pos.emisDue().signum() > 0 ? "LOAN REPAYMENT RISK" : "BALANCE RISK";

            if (amount.compareTo(pos.balance()) > 0) {
                loanStatus = "HIGH";

                cards.add(card(title, "This payment of " + Inr.of(amount) + " is more than your available balance of "
                        + Inr.of(pos.balance()) + ", so it is NOT covered by the money you have."));

            } else if (!pos.hasCommitments()) {
                if ("MEDIUM".equals(loanStatus)) {
                    cards.add(card(title, "After this payment only " + Inr.of(projected) + " of your " + Inr.of(pos.balance())
                            + " balance would be left. That leaves very little for anything unexpected."));
                }

            } else if ("HIGH".equals(loanStatus)) {
                cards.add(card(title, "You have " + Inr.of(pos.balance()) + " available. After this payment and " + pos.commitments()
                        + " " + pos.until() + ", your projected balance would be " + Inr.of(projected)
                        + ". That is NOT enough to cover them, so you may fall short."));

            } else if ("MEDIUM".equals(loanStatus)) {
                cards.add(card(title, "After this payment and " + pos.commitments() + " " + pos.until()
                        + ", your projected balance would be " + Inr.of(projected)
                        + ". That leaves very little for anything unexpected."));

            } else {
                notes.add(card("LOANS AND BILLS", "After this payment and " + pos.commitments() + " " + pos.until() + ", about "
                        + Inr.of(projected) + " is projected to be left."));
            }

            if ("HIGH".equals(loanStatus)) {
                BigDecimal safe = pos.balance().subtract(pos.emisDue()).subtract(pos.essentials()).subtract(pos.bills()).subtract(pos.buffer());

                act(cards, options, safe.signum() > 0
                        ? "Pay " + Inr.of(safe) + " or less to keep your known commitments covered."
                        : pos.payday() != null
                                ? "Postpone this payment until after your next income on " + Inr.date(pos.payday()) + "."
                                : "Postpone this payment or reduce the amount.");
            }

        } else {
            /* No balance was given: fall back to this month's budget, when there is one. */

            Map<String, Object> facts = BudgetRules.paymentFacts(data, amount, today);

            BigDecimal emis = (BigDecimal) facts.get("emiDueWithin30DaysInr");

            if (emis.signum() > 0 && facts.get("monthBudgetLeftAfterThisPaymentInr") instanceof BigDecimal left) {
                if (left.compareTo(emis) >= 0) {
                    loanStatus = "LOW";

                    notes.add(card("LOANS AND BILLS", "After this payment " + Inr.of(left)
                            + " of this month's budget is left, which still covers your EMIs of " + Inr.of(emis) + " due in the next 30 days."));

                } else {
                    loanStatus = "HIGH";

                    cards.add(card("LOAN REPAYMENT RISK", left.signum() > 0
                            ? "After this payment only " + Inr.of(left) + " of this month's budget is left, which is NOT enough for your EMIs of "
                                    + Inr.of(emis) + " due in the next 30 days."
                            : "After this payment nothing is left of this month's budget, so your EMIs of "
                                    + Inr.of(emis) + " due in the next 30 days are NOT covered."));

                    act(cards, options, "Postpone this payment or reduce the amount so your EMIs stay covered.");
                }
            }

            if ("UNKNOWN".equals(loanStatus) && (pos.emisDue().signum() > 0 || pos.bills().signum() > 0)) {
                /* What is known is still said, once: the commitments exist, even if the balance is not known. */

                StringBuilder known = new StringBuilder("You still have " + pos.commitments().substring("your ".length())
                        + " to pay " + pos.until() + ".");

                if (!pos.nextEmi().isEmpty()) {
                    known.append(" The next EMI is ").append(pos.nextEmi()).append(".");
                }

                known.append(" Monetra cannot check whether they stay covered, because your available balance is not set.");

                Map<String, String> loansNote = card("LOANS AND BILLS", known.toString());

                loansNote.put("action", "Add your available balance in Spending, under My money.");

                notes.add(loansNote);
            }
        }

        if (!pos.loans().isEmpty() || !"UNKNOWN".equals(loanStatus)) {
            impacts.add(impact(pos.loans().isEmpty() ? "Balance" : "Upcoming EMI", loanStatus));
        }

        /* 3. Savings goals. */

        String savingsStatus = "UNKNOWN";

        Map<String, Object> goal = savingsGoals.active(savingsGoals.goals(data, today));

        if (goal != null) {
            Map<String, Object> hit = savingsGoals.impact(goal, amount);

            BigDecimal saved = (BigDecimal) goal.get("saved");
            BigDecimal remaining = (BigDecimal) goal.get("remaining");
            BigDecimal shortfall = (BigDecimal) goal.get("shortfall");
            BigDecimal pace = (BigDecimal) goal.get("monthlyPace");

            String name = "\"" + goal.get("name") + "\"";

            StringBuilder text = new StringBuilder();

            if (saved.signum() > 0) {
                text.append("You are saving for ").append(name).append(" and have ").append(Inr.of(saved)).append(" of ")
                        .append(Inr.of((BigDecimal) goal.get("target"))).append(". If this payment comes from that savings, the amount you still need rises from ")
                        .append(Inr.of(remaining)).append(" to ").append(Inr.of((BigDecimal) hit.get("remainingAfter"))).append(".");

                if (hit.get("delayMonths") instanceof Long months) {
                    text.append(" At ").append(Inr.of(pace)).append(" a month, that is about ").append(months)
                            .append(months == 1 ? " more month" : " more months").append(" of saving.");
                }

            } else {
                text.append("You are saving for ").append(name).append(" and still need ").append(Inr.of(remaining))
                        .append(", with nothing saved yet.");
            }

            if (shortfall != null) {
                text.append(" You are already ").append(Inr.of(shortfall)).append(" behind your savings plan.");
            }

            boolean strained = "HIGH".equals(dailyStatus) || "MEDIUM".equals(dailyStatus) || "HIGH".equals(loanStatus);
            boolean large = amount.multiply(BigDecimal.TEN).compareTo(remaining) >= 0;

            if (shortfall != null && pace != null && amount.compareTo(pace) >= 0) {
                savingsStatus = "HIGH";
            } else if (strained || large || shortfall != null) {
                savingsStatus = "MEDIUM";
            } else {
                savingsStatus = "LOW";
            }

            if ("LOW".equals(savingsStatus)) {
                if (saved.signum() > 0) {
                    notes.add(card("SAVINGS GOAL", text.toString()));
                }

            } else {
                cards.add(card("SAVINGS GOAL IMPACT", text.toString()));

                act(cards, options, "Choose a lower-cost option or wait, so your " + name + " goal stays on track.");
            }

            impacts.add(impact("Savings goal", savingsStatus));
        }

        /*
         * When something needs a warning, everything else the user should know joins the same numbered
         * boxes, so each fact is shown once and in one place. Then the user decides.
         */

        boolean warned = !cards.isEmpty();

        if (warned) {
            cards.addAll(notes);
            cards.add(card("YOUR DECISION", "Review the payment and decide whether to continue."));
        }

        String overall = worst(dailyStatus, worst(loanStatus, savingsStatus));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("amount", BudgetRules.money(amount));
        out.put("overall", overall);
        out.put("overallLabel", "UNKNOWN".equals(overall) ? "NOT ENOUGH DATA" : overall + " FINANCIAL RISK");
        out.put("impacts", impacts);
        out.put("cards", cards);
        out.put("notes", warned ? List.of() : notes.stream().map(n -> n.get("text")).toList());
        out.put("options", options.size() > 3 ? options.subList(0, 3) : options);
        out.put("missing", missing);
        out.put("limitExceeded", !broken.isEmpty());
        out.put("daily", daily);
        return out;
    }

    /**
     * The review as a few plain sentences for the AI to put into words. Only what the user needs
     * to hear is sent: no account numbers, no history, nothing the review did not use.
     */
    public List<String> brief(Map<String, Object> review, String recipient, String purpose) {
        List<String> lines = new ArrayList<>();

        String to = recipient == null ? "" : recipient.trim();
        String why = purpose == null ? "" : purpose.trim();

        lines.add("You are about to pay " + Inr.of((BigDecimal) review.get("amount"))
                + (to.isEmpty() ? "" : " to " + to)
                + (why.isEmpty() ? "" : " for " + why) + ". The payment has not been made yet.");

        /* The spending limits come first: either the warning cards say they are broken, or this says they hold. */

        if (!Boolean.TRUE.equals(review.get("limitExceeded"))) {
            Map<?, ?> daily = (Map<?, ?>) review.get("daily");

            if (daily.get("tight") instanceof Map<?, ?> tight) {
                lines.add("This payment fits your " + tight.get("period") + " limit of " + Inr.of((BigDecimal) tight.get("limit"))
                        + " but leaves only " + Inr.of((BigDecimal) tight.get("left")) + " of it.");

            } else if (Boolean.TRUE.equals(daily.get("limitSet"))) {
                lines.add("This payment fits every spending limit you set. After it, "
                        + Inr.of(((BigDecimal) daily.get("remaining")).subtract((BigDecimal) review.get("amount")))
                        + " is left of your daily limit of " + Inr.of((BigDecimal) daily.get("limit")) + ".");

            } else if ("UNKNOWN".equals(daily.get("status"))) {
                lines.add("No spending limit is set, so the budget cannot be checked.");

            } else {
                lines.add("This payment fits every spending limit you set.");
            }
        }

        for (Object o : (List<?>) review.get("cards")) {
            Map<?, ?> c = (Map<?, ?>) o;

            if (!"YOUR DECISION".equals(c.get("title"))) {
                lines.add(String.valueOf(c.get("text")));
            }
        }

        for (Object note : (List<?>) review.get("notes")) {
            lines.add(String.valueOf(note));
        }

        List<String> out = new ArrayList<>(lines.size() > 6 ? lines.subList(0, 6) : lines);

        /* What the user could do, so the AI's advice can name these amounts instead of working out its own. */

        List<String> could = new ArrayList<>();

        for (Object option : (List<?>) review.get("options")) {
            could.add(String.valueOf(option));
        }

        if (!could.isEmpty()) {
            out.add("What you could do: " + String.join(" ", could));
        }

        return out;
    }

    /* ------------------------------------------------------------------ */
    /* The dashboard, the Loans page and the Savings page                  */
    /* ------------------------------------------------------------------ */

    /** Everything the three awareness cards and their pages show, worked out for today. */
    public Map<String, Object> overview(Long userId, LocalDate today) {
        JsonNode data = data(userId);

        Position pos = position(userId, data, today);

        Map<String, Object> out = new LinkedHashMap<>();

        /* Daily budget. */

        Map<String, Object> daily = dailyBudget.assess(data, BigDecimal.ZERO, today);

        daily.put("statusLabel", switch (String.valueOf(daily.get("status"))) {
            case "HIGH" -> "Over limit";
            case "MEDIUM" -> "Almost used";
            case "LOW" -> "On track";
            default -> "No limit set";
        });

        out.put("daily", daily);

        /* Loans. */

        Map<String, Object> loans = new LinkedHashMap<>();

        List<LoanRepaymentService.Installment> thisMonth =
                loanRepayment.schedule(pos.loans(), today, YearMonth.from(today).atEndOfMonth());

        List<Map<String, Object>> calendar = new ArrayList<>();

        for (LoanRepaymentService.Installment i : loanRepayment.schedule(pos.loans(), today, today.plusDays(60))) {
            if (calendar.size() >= 8) {
                break;
            }

            long days = LoanRepaymentService.daysUntil(today, i.date());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("loanId", i.loan().id());
            row.put("name", i.loan().name());
            row.put("amount", BudgetRules.money(i.loan().emi()));
            row.put("date", i.date().toString());
            row.put("overdue", i.overdue());
            row.put("inDays", days);
            calendar.add(row);
        }

        loans.put("count", pos.loans().size());
        loans.put("totalOutstanding", loanRepayment.outstanding(pos.loans()));
        loans.put("monthlyEmi", loanRepayment.monthlyEmi(pos.loans()));
        loans.put("emiThisMonth", loanRepayment.total(thisMonth));
        loans.put("calendar", calendar);
        loans.put("next", calendar.isEmpty() ? null : calendar.get(0));
        loans.put("reserved", pos.emisDue());
        loans.put("until", pos.until());

        String loanStatus = "UNKNOWN";
        String loanText;

        if (pos.balance() != null) {
            Map<String, Object> a = loanRepayment.assess(
                    pos.balance(), BigDecimal.ZERO, BigDecimal.ZERO, pos.emisDue(), pos.essentials(), pos.bills(), pos.buffer());

            BigDecimal projected = (BigDecimal) a.get("projectedBalance");

            loanStatus = String.valueOf(a.get("status"));

            loans.put("afterCommitments", projected);

            String base = pos.hasCommitments()
                    ? "After " + pos.commitments() + " " + pos.until() + ", your projected balance is " + Inr.of(projected) + "."
                    : "You have " + Inr.of(pos.balance()) + " available and no known commitments " + pos.until() + ".";

            loanText = switch (loanStatus) {
                case "HIGH" -> base + " That is NOT enough to cover them, so you may fall short.";
                case "MEDIUM" -> base + " That leaves very little for anything unexpected.";
                default -> base;
            };

        } else {
            loanText = pos.loans().isEmpty()
                    ? "Add a loan to see your EMIs here."
                    : "Add your balance in Spending, under My money, to check your EMIs.";
        }

        loans.put("status", loanStatus);
        loans.put("statusLabel", riskLabel(loanStatus));
        loans.put("text", loanText);

        out.put("loans", loans);

        /* Next month: income against the commitments already known for that month. */

        YearMonth next = YearMonth.from(today).plusMonths(1);

        BigDecimal emisNext = BigDecimal.ZERO;

        for (LoanRepaymentService.Installment i : loanRepayment.schedule(pos.loans(), today, next.atEndOfMonth())) {
            if (!i.date().isBefore(next.atDay(1))) {
                emisNext = emisNext.add(i.loan().emi());
            }
        }

        BigDecimal billsNext = BigDecimal.ZERO;

        for (HomeExpenseService.Row r : home.rows(userId, next.atDay(1), next.atEndOfMonth(), today)) {
            if (!"PAID".equals(r.status())) {
                billsNext = billsNext.add(r.expense().amount);
            }
        }

        List<Map<String, Object>> goals = savingsGoals.goals(data, today);

        BigDecimal contributions = BigDecimal.ZERO;

        for (Map<String, Object> g : goals) {
            if (!"REACHED".equals(g.get("status"))) {
                contributions = contributions.add((BigDecimal) g.get("plannedMonthly"));
            }
        }

        Map<String, Object> nextMonth = forecasts.nextMonth(
                pos.monthlyIncome(), emisNext, pos.essentialsMonthly(), contributions, billsNext, null);

        nextMonth.put("month", next.toString());

        if (Boolean.TRUE.equals(nextMonth.get("known"))) {
            BigDecimal remaining = (BigDecimal) nextMonth.get("projectedRemaining");

            nextMonth.put("text", remaining.signum() < 0
                    ? "Next month's known commitments are " + Inr.of(remaining.negate()) + " more than your expected income. That month could be difficult to manage."
                    : "After next month's known commitments, about " + Inr.of(remaining) + " of your expected income is projected to be left.");
        } else {
            nextMonth.put("text", "Add your monthly income in Spending to see this.");
        }

        out.put("nextMonth", nextMonth);

        /* Savings goals, with advice that never asks for more than is free after EMIs and essentials. */

        BigDecimal free = pos.monthlyIncome().signum() > 0
                ? pos.monthlyIncome().subtract(loanRepayment.monthlyEmi(pos.loans())).subtract(pos.essentialsMonthly())
                : null;

        for (Map<String, Object> g : goals) {
            String status = String.valueOf(g.get("status"));

            BigDecimal required = (BigDecimal) g.get("requiredMonthly");

            if ("DATE_PASSED".equals(status)) {
                g.put("advice", "The target date has passed. Choose a new date or update this goal.");

            } else if ("BEHIND".equals(status)) {
                g.put("advice", "Savings alert: you are " + Inr.of((BigDecimal) g.get("shortfall")) + " behind your plan. To still reach it by "
                        + Inr.date(LocalDate.parse(String.valueOf(g.get("targetDate")))) + " you now need about " + Inr.of(required)
                        + " a month. You can raise your monthly saving, lower the target or move the date.");
            }

            if (required != null && free != null && required.compareTo(free.max(BigDecimal.ZERO)) > 0) {
                g.put("affordable", false);
                g.put("advice", (g.containsKey("advice") ? g.get("advice") + " " : "")
                        + "This goal needs " + Inr.of(required) + " a month, but after your EMIs and essential expenses only about "
                        + Inr.of(free.max(BigDecimal.ZERO)) + " a month is free. Extend the date or lower the target.");
            }
        }

        out.put("goals", goals);
        out.put("activeGoal", savingsGoals.active(goals));

        out.put("safeToSpend", safeToSpend(pos, data, goals, today));

        /* The money figures behind all of this. */

        Map<String, Object> money = new LinkedHashMap<>();
        money.put("balanceKnown", pos.balance() != null);
        money.put("balance", pos.balance());
        money.put("nextPayday", pos.payday() == null ? null : pos.payday().toString());
        money.put("monthlyIncome", pos.monthlyIncome());
        money.put("essentialsMonthly", pos.essentialsMonthly());
        money.put("bills", pos.bills());

        out.put("money", money);

        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Notifications                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Creates the Guardian's notifications that are due now: daily limit, EMIs, savings goals and
     * home expense reminders. Each one is created once. Runs when the user's app asks for its
     * notifications, so nothing is sent while Monetra is closed.
     */
    public int createNotifications(Long userId, LocalDate today, LocalTime now) {
        JsonNode data = data(userId);

        JsonNode prefs = data.path("profile").path("notify");

        int created = 0;

        /* Daily spending. */

        if (prefs.path("daily").asBoolean(true)) {
            Map<String, Object> daily = dailyBudget.assess(data, BigDecimal.ZERO, today);

            if (Boolean.TRUE.equals(daily.get("limitSet"))) {
                BigDecimal limit = (BigDecimal) daily.get("limit");
                int used = (Integer) daily.get("percentUsed");

                if (daily.get("overBy") instanceof BigDecimal over && over.signum() > 0) {
                    created += alert(userId, "DAILY_OVER:" + today, "You are " + Inr.of(over) + " over today's spending limit of " + Inr.of(limit) + ".");

                } else if (used >= 80) {
                    created += alert(userId, "DAILY_NEAR:" + today, "You have used " + used + "% of today's spending limit of " + Inr.of(limit) + ".");
                }
            }

            BigDecimal usual = usualDailySpend(data, today);
            BigDecimal spent = (BigDecimal) daily.get("spentToday");

            if (usual != null && spent.compareTo(usual.multiply(BigDecimal.valueOf(3))) > 0) {
                created += alert(userId, "DAILY_UNUSUAL:" + today, "Today's spending of " + Inr.of(spent)
                        + " is more than 3 times your usual day of about " + Inr.of(usual) + ".");
            }
        }

        /* Loans. */

        Position pos = position(userId, data, today);

        if (prefs.path("loans").asBoolean(true)) {
            for (LoanRepaymentService.Installment i : loanRepayment.schedule(pos.loans(), today, today.plusDays(3))) {
                String what = "Your EMI \"" + i.loan().name() + "\" of " + Inr.of(i.loan().emi());
                String key = i.loan().id() + ":" + i.date();

                if (i.overdue()) {
                    created += alert(userId, "EMI_OVERDUE:" + key, what + " was due on " + Inr.date(i.date()) + " and is not marked paid.");

                } else if (i.date().equals(today)) {
                    created += alert(userId, "EMI_TODAY:" + key, what + " is due today.");

                } else {
                    created += alert(userId, "EMI_SOON:" + key, what + " is due on " + Inr.date(i.date()) + ".");
                }
            }

            if (pos.balance() != null && pos.emisDue().signum() > 0) {
                BigDecimal projected = pos.balance().subtract(pos.emisDue()).subtract(pos.essentials()).subtract(pos.bills());

                if (projected.signum() < 0) {
                    created += alert(userId, "EMI_SHORT:" + pos.horizon(), "Your balance of " + Inr.of(pos.balance())
                            + " may not cover " + pos.commitments() + " " + pos.until() + ".");
                }
            }
        }

        /* Savings goals. */

        if (prefs.path("savings").asBoolean(true)) {
            JsonNode raw = data.path("goals");

            int index = 0;

            for (Map<String, Object> g : savingsGoals.goals(data, today)) {
                JsonNode source = raw.path(index++);

                String id = String.valueOf(g.get("id"));
                String name = "\"" + g.get("name") + "\"";
                String month = YearMonth.from(today).toString();

                if ("REACHED".equals(g.get("status"))) {
                    created += alert(userId, "GOAL_DONE:" + id, "You reached your savings goal " + name + ". Well done!");
                    continue;
                }

                if (g.get("shortfall") instanceof BigDecimal shortfall) {
                    created += alert(userId, "GOAL_BEHIND:" + id + ":" + month,
                            "You are " + Inr.of(shortfall) + " behind your savings plan for " + name + ".");
                }

                int percent = (Integer) g.get("percent");

                for (int step : new int[]{75, 50, 25}) {
                    if (percent >= step) {
                        created += alert(userId, "GOAL_PCT:" + id + ":" + step, "Your savings goal " + name + " is " + step + "% of the way there.");
                        break;
                    }
                }

                int remindDay = source.path("remindDay").asInt(0);

                BigDecimal planned = (BigDecimal) g.get("plannedMonthly");

                if (remindDay >= 1 && remindDay == Math.min(today.getDayOfMonth(), 28) && planned.signum() > 0) {
                    created += alert(userId, "GOAL_SAVE:" + id + ":" + month,
                            "Time to save " + Inr.of(planned) + " for " + name + " this month.");
                }
            }
        }

        /* Home expense (HE) reminders the user switched on. */

        JsonNode he = data.path("he").path("reminders");

        if (due(he.path("daily"), now)) {
            created += reminder(userId, "HE_DAILY:" + today, "HE reminder: time to pay today's home expenses.");
        }

        JsonNode monthly = he.path("monthly");

        if (due(monthly, now) && Math.min(monthly.path("day").asInt(1), today.lengthOfMonth()) == today.getDayOfMonth()) {
            created += reminder(userId, "HE_MONTHLY:" + YearMonth.from(today), "HE reminder: time to pay this month's home expenses.");
        }

        JsonNode yearly = he.path("yearly");

        if (due(yearly, now) && yearly.path("date").asText("").endsWith(today.toString().substring(5))) {
            created += reminder(userId, "HE_YEARLY:" + today.getYear(), "HE reminder: time to pay this year's home expenses.");
        }

        return created;
    }

    /** A reminder is due when it is switched on and its time of day has come. */
    private static boolean due(JsonNode reminder, LocalTime now) {
        if (!reminder.path("on").asBoolean(false)) {
            return false;
        }

        try {
            return !now.isBefore(LocalTime.parse(reminder.path("time").asText("09:00")));

        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Safe to spend today. The money the user had when the day began, minus everything that has to be
     * paid before the next income (EMIs, household bills, essential expenses, planned goal savings and
     * the emergency buffer), shared over the days until then. What was already spent today comes off.
     * Only the user's own balance is used: nothing borrowed is counted.
     */
    Map<String, Object> safeToSpend(Position pos, JsonNode data, List<Map<String, Object>> goals, LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>();

        if (pos.balance() == null) {
            out.put("known", false);
            out.put("text", "Add your balance and income day in Spending, under My money.");
            return out;
        }

        /* Spent today, apart from EMIs: an EMI paid today already left the list of what is still due. */

        BigDecimal spentToday = BigDecimal.ZERO;

        for (JsonNode e : data.path("expenses")) {
            if (today.toString().equals(e.path("date").asText("")) && !"emi".equals(e.path("source").asText(""))) {
                spentToday = spentToday.add(BudgetRules.decimal(e.path("amount")));
            }
        }

        BigDecimal days = BigDecimal.valueOf(Math.max(1, pos.days()));

        /* Each unfinished goal's saving for the days until the next income, never more than it still needs. */

        BigDecimal savings = BigDecimal.ZERO;

        for (Map<String, Object> g : goals) {
            if ("REACHED".equals(g.get("status"))) {
                continue;
            }

            BigDecimal monthly = (BigDecimal) g.get("plannedMonthly");

            if (monthly.signum() <= 0 && g.get("requiredMonthly") instanceof BigDecimal required) {
                monthly = required;
            }

            BigDecimal share = monthly.multiply(days).divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);

            savings = savings.add(share.min((BigDecimal) g.get("remaining")));
        }

        BigDecimal protectedTotal = BudgetRules.money(
                pos.emisDue().add(pos.bills()).add(pos.essentials()).add(savings).add(pos.buffer()));

        BigDecimal free = pos.balance().add(spentToday).subtract(protectedTotal);

        BigDecimal perDay = free.signum() > 0
                ? free.divide(days, 0, RoundingMode.DOWN)
                : BigDecimal.ZERO;

        BigDecimal safe = perDay.subtract(spentToday).max(BigDecimal.ZERO);

        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("emis", pos.emisDue());
        parts.put("bills", pos.bills());
        parts.put("essentials", pos.essentials());
        parts.put("savings", BudgetRules.money(savings));
        parts.put("buffer", pos.buffer());

        out.put("known", true);
        out.put("amount", BudgetRules.money(safe));
        out.put("perDay", BudgetRules.money(perDay));
        out.put("spentToday", BudgetRules.money(spentToday));
        out.put("balance", pos.balance());
        out.put("protected", protectedTotal);
        out.put("parts", parts);
        out.put("until", pos.until());
        out.put("days", pos.days());

        String status;
        String text;

        if (free.signum() <= 0) {
            status = "HIGH";
            text = "Nothing is safe to spend today. Your money does not cover the " + Inr.of(protectedTotal)
                    + " for your EMIs, bills, essentials and savings " + pos.until() + ".";

        } else if (spentToday.compareTo(perDay) > 0) {
            status = "HIGH";
            text = "You have spent " + Inr.of(spentToday) + " today, " + Inr.of(spentToday.subtract(perDay))
                    + " more than today's safe amount of " + Inr.of(perDay) + ". Your EMIs and bills " + pos.until() + " now have less room.";

        } else {
            status = safe.multiply(BigDecimal.valueOf(4)).compareTo(perDay) < 0 ? "MEDIUM" : "LOW";
            text = "Your EMIs, bills, essentials and planned savings " + pos.until() + " (" + Inr.of(protectedTotal)
                    + ") are protected. You can spend up to " + Inr.of(safe) + " today"
                    + (spentToday.signum() > 0 ? ", after the " + Inr.of(spentToday) + " you already spent." : ".");
        }

        out.put("status", status);
        out.put("text", text);
        return out;
    }

    /** The user's average spending on the days they spent, over the two weeks before today. Null without enough history. */
    private static BigDecimal usualDailySpend(JsonNode data, LocalDate today) {
        Map<String, BigDecimal> byDay = new LinkedHashMap<>();

        String from = today.minusDays(14).toString();
        String until = today.minusDays(1).toString();

        for (JsonNode e : data.path("expenses")) {
            String date = e.path("date").asText("");

            if (date.compareTo(from) >= 0 && date.compareTo(until) <= 0) {
                byDay.merge(date, BudgetRules.decimal(e.path("amount")), (a, b) -> a.add(b));
            }
        }

        if (byDay.size() < 5) {
            return null;
        }

        BigDecimal total = byDay.values().stream().reduce(BigDecimal.ZERO, (a, b) -> a.add(b));

        return total.divide(BigDecimal.valueOf(byDay.size()), 2, RoundingMode.HALF_UP);
    }

    private int alert(Long userId, String key, String message) {
        return notify(userId, key, "ALERT", message);
    }

    private int reminder(Long userId, String key, String message) {
        return notify(userId, key, "REMINDER", message);
    }

    /** Saves a notification unless one with the same key was already sent to this user. */
    private int notify(Long userId, String key, String type, String message) {
        String alertKey = key.length() > 80 ? key.substring(0, 80) : key;

        if (alerts.existsByUserIdAndAlertKey(userId, alertKey)) {
            return 0;
        }

        GuardianAlert sent = new GuardianAlert();
        sent.userId = userId;
        sent.alertKey = alertKey;

        try {
            alerts.save(sent);

        } catch (RuntimeException duplicate) {
            return 0;   /* another request created it first */
        }

        notifications.save(new Notification(userId, type, message.length() > 300 ? message.substring(0, 300) : message, null));

        return 1;
    }

    /* ------------------------------------------------------------------ */

    private static Map<String, String> card(String title, String text) {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("title", title);
        c.put("text", text);
        return c;
    }

    /** What the user could do about the box just added: kept in that box, and in the list of options. */
    private static void act(List<Map<String, String>> cards, List<String> options, String action) {
        options.add(action);

        if (!cards.isEmpty()) {
            cards.get(cards.size() - 1).put("action", action);
        }
    }

    private static Map<String, String> impact(String area, String level) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("area", area);
        m.put("level", level);
        m.put("label", "HIGH".equals(level) ? "High" : "MEDIUM".equals(level) ? "Medium" : "LOW".equals(level) ? "Low" : "Not enough data");
        return m;
    }

    private static String riskLabel(String status) {
        return switch (status) {
            case "HIGH" -> "High risk";
            case "MEDIUM" -> "Medium risk";
            case "LOW" -> "Low risk";
            default -> "Not enough data";
        };
    }

    /** "333" for a big ratio, "1.5" for a small one. */
    private static String times(BigDecimal ratio) {
        return ratio.compareTo(BigDecimal.TEN) >= 0
                ? ratio.setScale(0, RoundingMode.HALF_UP).toPlainString()
                : ratio.stripTrailingZeros().toPlainString();
    }

    private static String worst(String a, String b) {
        return rank(b) > rank(a) ? b : a;
    }

    private static int rank(String status) {
        return "HIGH".equals(status) ? 3 : "MEDIUM".equals(status) ? 2 : "LOW".equals(status) ? 1 : 0;
    }
}
