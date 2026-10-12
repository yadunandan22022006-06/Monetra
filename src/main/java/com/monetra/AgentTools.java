package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools the AI Financial Guardian can call. Every tool reads the logged-in user's own saved data
 * and does plain arithmetic in Java. Tools that look like actions (prepare_payment, prepare_reminder)
 * only PREPARE something: nothing is sent or saved until the user confirms in the app.
 */
@Component
public class AgentTools {

    private final UserDataRepository store;
    private final HomeExpenseService home;
    private final ContactService contacts;
    private final RecipientResolver recipients;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentTools(
            UserDataRepository store,
            HomeExpenseService home,
            ContactService contacts,
            RecipientResolver recipients) {

        this.store = store;
        this.home = home;
        this.contacts = contacts;
        this.recipients = recipients;
    }

    /* ------------------------------------------------------------------ */
    /* Tool descriptions sent to the model                                 */
    /* ------------------------------------------------------------------ */

    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        tools.add(tool("check_budget",
                "Get the user's spending limits (daily, weekly, monthly, yearly), how much is spent in each, "
                        + "the month-end forecast, and the recorded balance if there is one.",
                Map.of(), List.of()));

        tools.add(tool("get_upcoming_expenses",
                "List unpaid and upcoming household expenses and EMIs due in the next days, with totals.",
                Map.of("days", prop("integer", "How many days ahead to look (1 to 60). Default 7.")),
                List.of()));

        tools.add(tool("check_affordability",
                "Check whether the user can afford a purchase. Looks at the limits, recorded balance, "
                        + "upcoming household expenses and EMIs.",
                Map.of(
                        "amountInr", prop("number", "The amount in rupees."),
                        "purpose", prop("string", "What it is for, for example shoes.")),
                List.of("amountInr")));

        tools.add(tool("prepare_payment",
                "Prepare a payment for the user to review. It does NOT send money. Needs a recipient "
                        + "(a 10-digit mobile number or the exact name of a saved contact) and an amount in rupees.",
                Map.of(
                        "recipient", prop("string", "A 10-digit mobile number or a saved contact's name."),
                        "amountInr", prop("number", "The amount in rupees."),
                        "note", prop("string", "What the payment is for (optional).")),
                List.of("recipient", "amountInr")));

        tools.add(tool("get_home_contributions",
                "Get household expenses and money given at home for a month: what was paid, what is outstanding, "
                        + "and the totals by category.",
                Map.of("month", prop("string", "Month as YYYY-MM. Default is this month.")),
                List.of()));

        tools.add(tool("prepare_reminder",
                "Prepare a recurring household reminder for the user to confirm. It does NOT save anything and "
                        + "never moves money.",
                Map.of(
                        "name", prop("string", "What the reminder is for."),
                        "amountInr", prop("number", "The amount in rupees."),
                        "frequency", prop("string", "ONCE, DAILY, WEEKLY, MONTHLY or YEARLY."),
                        "dayOfMonth", prop("integer", "For monthly reminders: the day of the month (1 to 31)."),
                        "startDate", prop("string", "First due date as YYYY-MM-DD, if known."),
                        "category", prop("string", "One of: " + String.join(", ", HomeExpenseRules.CATEGORIES))),
                List.of("name", "amountInr", "frequency")));

        return tools;
    }

    /* ------------------------------------------------------------------ */
    /* Running a tool                                                      */
    /* ------------------------------------------------------------------ */

    /** Runs one tool. Throws IllegalArgumentException with a message the model can pass on. */
    public Map<String, Object> run(Long userId, String name, Map<String, Object> input) {
        LocalDate today = LocalDate.now();

        return switch (name) {
            case "check_budget" -> checkBudget(userId, today);
            case "get_upcoming_expenses" -> upcoming(userId, today, intIn(input.get("days"), 7, 1, 60));
            case "check_affordability" -> affordability(userId, today, amount(input.get("amountInr")),
                    RecipientResolver.text(input.get("purpose"), 60));
            case "prepare_payment" -> preparePayment(userId, today, input);
            case "get_home_contributions" -> contributions(userId, today, input.get("month"));
            case "prepare_reminder" -> prepareReminder(userId, today, input);
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        };
    }

    private Map<String, Object> checkBudget(Long userId, LocalDate today) {
        JsonNode data = data(userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("budget", BudgetRules.check(data, BigDecimal.ZERO, today));
        out.put("forecast", BudgetRules.forecast(data, today));
        out.put("recordedBalanceInr", recordedBalance(data));
        out.put("note", "The balance is the user's own recorded income minus recorded expenses, not a bank balance.");
        return out;
    }

    private Map<String, Object> upcoming(Long userId, LocalDate today, int days) {
        List<Map<String, Object>> expenses = new ArrayList<>();

        BigDecimal homeTotal = BigDecimal.ZERO;

        for (HomeExpenseService.Row r : home.actionable(userId, today, days)) {
            expenses.add(r.toMap());
            homeTotal = homeTotal.add(r.expense().amount);
        }

        List<Map<String, Object>> emis = new ArrayList<>();

        BigDecimal emiTotal = BigDecimal.ZERO;

        for (JsonNode loan : data(userId).path("loans")) {
            String due = loan.path("due").asText("");

            try {
                LocalDate d = LocalDate.parse(due);

                if (!d.isBefore(today) && !d.isAfter(today.plusDays(days))) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", loan.path("name").asText(""));
                    m.put("amountInr", loan.path("amount").decimalValue());
                    m.put("dueDate", due);
                    emis.add(m);
                    emiTotal = emiTotal.add(loan.path("amount").decimalValue());
                }

            } catch (Exception ignored) {
                /* a loan without a valid date is skipped */
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("householdExpenses", expenses);
        out.put("emis", emis);
        out.put("householdTotalInr", homeTotal);
        out.put("emiTotalInr", emiTotal);
        out.put("totalInr", homeTotal.add(emiTotal));
        return out;
    }

    private Map<String, Object> affordability(Long userId, LocalDate today, BigDecimal amount, String purpose) {
        JsonNode data = data(userId);

        Map<String, Object> budget = BudgetRules.check(data, amount, today);

        Map<String, Object> next7 = upcoming(userId, today, 7);
        BigDecimal obligations = (BigDecimal) next7.get("totalInr");

        BigDecimal balance = recordedBalance(data);
        BigDecimal leftAfter = balance == null ? null : balance.subtract(amount).subtract(obligations);

        List<String> reasons = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<String> breaches = (List<String>) budget.get("breaches");

        for (String period : breaches) {
            reasons.add("It is over your " + period + " limit.");
        }

        if (leftAfter != null && leftAfter.signum() < 0) {
            reasons.add("After upcoming household expenses and EMIs, your recorded balance would be short by ₹"
                    + leftAfter.abs().setScale(2, RoundingMode.HALF_UP).toPlainString() + ".");
        }

        if (Boolean.TRUE.equals(budget.get("emiAtRisk"))) {
            reasons.add("It may leave too little this month for the EMI due soon.");
        }

        String verdict;

        if (!reasons.isEmpty()) {
            verdict = "not_affordable";

        } else if ("low".equals(budget.get("level"))
                || (leftAfter != null && balance != null && leftAfter.compareTo(balance.multiply(new BigDecimal("0.10"))) < 0)) {
            verdict = "tight";

        } else if ("unknown".equals(budget.get("level")) && balance == null) {
            verdict = "unknown";

        } else {
            verdict = "affordable";
        }


        Map<String, Object> out = new LinkedHashMap<>();
        out.put("purpose", purpose);
        out.put("amountInr", amount);
        out.put("verdict", verdict);
        out.put("reasons", reasons);
        out.put("recordedBalanceInr", balance);
        out.put("upcomingObligationsNext7DaysInr", obligations);
        out.put("balanceAfterObligationsAndPurchaseInr", leftAfter);
        out.put("budget", budget);
        return out;
    }

    private Map<String, Object> preparePayment(Long userId, LocalDate today, Map<String, Object> input) {
        String who = RecipientResolver.text(input.get("recipient"), 60);
        BigDecimal amount = amount(input.get("amountInr"));

        String phone = Phones.key(who);

        if (phone == null) {
            Map<String, Object> contact = contacts.uniqueByName(userId, who);

            if (contact == null) {
                throw new IllegalArgumentException(
                        "I could not find exactly one saved contact called \"" + who
                                + "\". Ask the user for the person's 10-digit mobile number.");
            }

            phone = String.valueOf(contact.get("phone"));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("method", "PHONE");
        body.put("recipientPhone", phone);

        RecipientResolver.Recipient to = recipients.resolve(body, userId);

        Map<String, Object> budget = BudgetRules.check(data(userId), amount, today);

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("method", "PHONE");
        draft.put("recipientPhone", phone);
        draft.put("amountInr", amount);
        draft.put("description", RecipientResolver.text(input.get("note"), 100));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prepared", true);
        out.put("sent", false);
        out.put("recipientName", to.name());
        out.put("onMonetra", to.userId() != null);
        out.put("draft", draft);
        out.put("budgetLevel", budget.get("level"));
        out.put("budgetBreaches", budget.get("breaches"));
        out.put("note", "Nothing was sent. The user must review and confirm this payment in the app.");
        return out;
    }

    private Map<String, Object> contributions(Long userId, LocalDate today, Object monthInput) {
        YearMonth month;

        try {
            month = monthInput == null || String.valueOf(monthInput).isBlank()
                    ? YearMonth.from(today) : YearMonth.parse(String.valueOf(monthInput));

        } catch (Exception e) {
            throw new IllegalArgumentException("Use a month like 2026-10.");
        }

        List<Map<String, Object>> items = new ArrayList<>();

        for (HomeExpenseService.Row r : home.rows(userId, month.atDay(1), month.atEndOfMonth(), today)) {
            items.add(r.toMap());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("report", home.report(userId, month, today));
        out.put("items", items);
        out.put("note", "Payments marked MANUAL were recorded by the user. PAYPAL ones were confirmed through PayPal.");
        return out;
    }

    private Map<String, Object> prepareReminder(Long userId, LocalDate today, Map<String, Object> input) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", input.get("name"));
        fields.put("amount", input.get("amountInr"));
        fields.put("frequency", input.get("frequency"));
        fields.put("category", input.get("category"));
        fields.put("remindDaysBefore", 1);

        Object start = input.get("startDate");

        if ((start == null || String.valueOf(start).isBlank()) && input.get("dayOfMonth") != null) {
            int day = intIn(input.get("dayOfMonth"), today.getDayOfMonth(), 1, 31);

            LocalDate candidate = YearMonth.from(today).atDay(Math.min(day, YearMonth.from(today).lengthOfMonth()));

            if (candidate.isBefore(today)) {
                YearMonth next = YearMonth.from(today).plusMonths(1);

                candidate = next.atDay(Math.min(day, next.lengthOfMonth()));
            }

            start = candidate.toString();
        }

        fields.put("startDate", start == null ? today.toString() : start);

        HomeExpense checked = home.build(userId, fields);

        Map<String, Object> reminder = new LinkedHashMap<>();
        reminder.put("name", checked.name);
        reminder.put("amount", checked.amount);
        reminder.put("frequency", checked.frequency);
        reminder.put("startDate", checked.startDate.toString());
        reminder.put("category", checked.category);
        reminder.put("remindDaysBefore", checked.remindDaysBefore);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("prepared", true);
        out.put("saved", false);
        out.put("reminder", reminder);
        out.put("note", "Nothing was saved. The user must confirm the reminder in the app. A reminder never moves money.");
        return out;
    }

    /* ------------------------------------------------------------------ */

    private JsonNode data(Long userId) {
        try {
            UserData row = store.findById(userId).orElse(null);

            return mapper.readTree(row == null ? "{}" : row.getJson());

        } catch (Exception e) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
    }

    /** Recorded income minus recorded expenses, or null if no income has been recorded. */
    private static BigDecimal recordedBalance(JsonNode data) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal spent = BigDecimal.ZERO;

        for (JsonNode i : data.path("income")) {
            income = income.add(i.path("amount").decimalValue());
        }

        for (JsonNode e : data.path("expenses")) {
            spent = spent.add(e.path("amount").decimalValue());
        }

        return income.signum() == 0 ? null : income.subtract(spent).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal amount(Object value) {
        BigDecimal a;

        try {
            a = new BigDecimal(String.valueOf(value)).setScale(2, RoundingMode.HALF_UP);
        } catch (Exception e) {
            throw new IllegalArgumentException("The amount must be a number of rupees.");
        }

        if (a.signum() <= 0 || a.compareTo(new BigDecimal("10000000")) > 0) {
            throw new IllegalArgumentException("The amount must be between ₹0.01 and ₹1,00,00,000.");
        }

        return a;
    }

    private static int intIn(Object value, int fallback, int min, int max) {
        try {
            int n = value == null ? fallback : (int) Math.round(Double.parseDouble(String.valueOf(value)));

            return Math.max(min, Math.min(max, n));

        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("input_schema", schema);
        return t;
    }
}
