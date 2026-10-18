package com.monetra;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Household expenses: schedules, paid and unpaid tracking, summaries, reports and reminders. */
@Service
public class HomeExpenseService {

    /** How far back unpaid due dates still count as overdue. */
    static final int OVERDUE_LOOKBACK_DAYS = 14;

    /** One due date of one expense. */
    public record Row(
            HomeExpense expense,
            LocalDate due,
            String status,
            String paidSource,
            String paymentId) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("expenseId", expense.id);
            m.put("name", expense.name);
            m.put("category", expense.category);
            m.put("amount", expense.amount);
            m.put("frequency", expense.frequency);
            m.put("dueDate", due.toString());
            m.put("status", status);
            m.put("paidSource", paidSource);
            m.put("recipientPhone", expense.recipientPhone);
            return m;
        }
    }

    private final HomeExpenseRepository expenses;
    private final HomeExpensePaymentRepository paid;
    private final ReminderSentRepository reminders;
    private final NotificationRepository notifications;

    public HomeExpenseService(
            HomeExpenseRepository expenses,
            HomeExpensePaymentRepository paid,
            ReminderSentRepository reminders,
            NotificationRepository notifications) {

        this.expenses = expenses;
        this.paid = paid;
        this.reminders = reminders;
        this.notifications = notifications;
    }

    /* ------------------------------------------------------------------ */
    /* Create and delete                                                   */
    /* ------------------------------------------------------------------ */

    /** Validates and saves a new expense. Throws IllegalArgumentException with a friendly message. */
    public HomeExpense create(Long userId, Map<String, Object> body) {
        return expenses.save(build(userId, body));
    }

    /** Validates the fields and builds an expense without saving it. */
    public HomeExpense build(Long userId, Map<String, Object> body) {
        String name = text(body.get("name"), 60);

        if (name.isEmpty()) {
            throw new IllegalArgumentException("Enter a name for this expense.");
        }

        BigDecimal amount;

        try {
            amount = new BigDecimal(text(body.get("amount"), 20)).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid amount.");
        }

        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("10000000")) > 0) {
            throw new IllegalArgumentException("Enter an amount between ₹0.01 and ₹1,00,00,000.");
        }

        String frequency = text(body.get("frequency"), 8).toUpperCase();

        if (!HomeExpenseRules.FREQUENCIES.contains(frequency)) {
            throw new IllegalArgumentException("Choose how often this repeats.");
        }

        LocalDate start;

        try {
            start = LocalDate.parse(text(body.get("startDate"), 10));
        } catch (Exception e) {
            throw new IllegalArgumentException("Choose a valid first due date.");
        }

        if (start.isAfter(LocalDate.now().plusYears(5)) || start.isBefore(LocalDate.now().minusYears(2))) {
            throw new IllegalArgumentException("Choose a due date within the last 2 years and the next 5 years.");
        }

        String category = text(body.get("category"), 30);

        if (!HomeExpenseRules.CATEGORIES.contains(category)) {
            category = "Other";
        }

        int remind;

        try {
            remind = Integer.parseInt(text(body.get("remindDaysBefore"), 3).isEmpty()
                    ? "1" : text(body.get("remindDaysBefore"), 3));
        } catch (NumberFormatException e) {
            remind = 1;
        }

        remind = Math.max(0, Math.min(14, remind));

        String phoneText = text(body.get("recipientPhone"), 20);
        String phone = Phones.key(phoneText);

        if (!phoneText.isEmpty() && phone == null) {
            throw new IllegalArgumentException("That mobile number does not look right.");
        }

        HomeExpense e = new HomeExpense();
        e.userId = userId;
        e.name = name;
        e.amount = amount;
        e.category = category;
        e.frequency = frequency;
        e.startDate = start;
        e.recipientPhone = phone;
        e.note = text(body.get("note"), 100);
        e.remindDaysBefore = remind;

        return e;
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean delete(Long userId, Long expenseId) {
        HomeExpense e = expenses.findByIdAndUserId(expenseId, userId).orElse(null);

        if (e == null) {
            return false;
        }

        paid.deleteByExpenseId(e.id);
        reminders.deleteByExpenseId(e.id);
        expenses.delete(e);

        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Schedules and status                                                */
    /* ------------------------------------------------------------------ */

    /** Every due date between the two days, with its paid or unpaid status, sorted by date. */
    public List<Row> rows(Long userId, LocalDate from, LocalDate to, LocalDate today) {
        List<HomeExpense> list = expenses.findByUserIdAndActiveTrueOrderByCreatedAtAsc(userId);

        if (list.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, HomeExpensePayment> payments = paid.findByExpenseIdIn(
                        list.stream().map(x -> x.id).collect(Collectors.toList()))
                .stream()
                .collect(Collectors.toMap(p -> p.expenseId + "|" + p.dueDate, p -> p, (a, b) -> a));

        List<Row> out = new ArrayList<>();

        for (HomeExpense e : list) {
            for (LocalDate due : HomeExpenseRules.occurrences(e.frequency, e.startDate, from, to)) {
                HomeExpensePayment p = payments.get(e.id + "|" + due);

                out.add(new Row(
                        e, due,
                        HomeExpenseRules.status(due, p != null, today),
                        p == null ? null : p.source,
                        p == null ? null : p.paymentId));
            }
        }

        out.sort(Comparator.comparing((Row r) -> r.due()).thenComparing(r -> r.expense().name));

        return out;
    }

    /** What the user needs to act on: overdue, due today and coming up within the next days. */
    public List<Row> actionable(Long userId, LocalDate today, int days) {
        return rows(userId, today.minusDays(OVERDUE_LOOKBACK_DAYS), today.plusDays(days), today).stream()
                .filter(r -> !"PAID".equals(r.status()))
                .collect(Collectors.toList());
    }

    /** Today's summary: how many are due, how many paid, how much is outstanding. */
    public Map<String, Object> summaryToday(Long userId, LocalDate today) {
        List<Row> dueToday = rows(userId, today, today, today);
        List<Row> overdue = rows(userId, today.minusDays(OVERDUE_LOOKBACK_DAYS), today.minusDays(1), today).stream()
                .filter(r -> !"PAID".equals(r.status()))
                .collect(Collectors.toList());

        BigDecimal paidAmount = BigDecimal.ZERO;
        BigDecimal pendingAmount = BigDecimal.ZERO;
        int paidCount = 0;

        for (Row r : dueToday) {
            if ("PAID".equals(r.status())) {
                paidCount++;
                paidAmount = paidAmount.add(r.expense().amount);
            } else {
                pendingAmount = pendingAmount.add(r.expense().amount);
            }
        }

        BigDecimal overdueAmount = overdue.stream()
                .map(r -> r.expense().amount)
                .reduce(BigDecimal.ZERO, (a, b) -> a.add(b));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", today.toString());
        out.put("dueCount", dueToday.size());
        out.put("paidCount", paidCount);
        out.put("pendingCount", dueToday.size() - paidCount);
        out.put("paidAmount", paidAmount);
        out.put("pendingAmount", pendingAmount);
        out.put("overdueCount", overdue.size());
        out.put("overdueAmount", overdueAmount);
        return out;
    }

    /** A month's report grouped by category. */
    public Map<String, Object> report(Long userId, YearMonth month, LocalDate today) {
        List<Row> rows = rows(userId, month.atDay(1), month.atEndOfMonth(), today);

        Map<String, BigDecimal[]> byCategory = new LinkedHashMap<>();

        BigDecimal totalPaid = BigDecimal.ZERO;
        BigDecimal totalOutstanding = BigDecimal.ZERO;

        for (Row r : rows) {
            BigDecimal[] sums = byCategory.computeIfAbsent(
                    r.expense().category == null ? "Other" : r.expense().category,
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});

            if ("PAID".equals(r.status())) {
                sums[0] = sums[0].add(r.expense().amount);
                totalPaid = totalPaid.add(r.expense().amount);
            } else {
                sums[1] = sums[1].add(r.expense().amount);
                totalOutstanding = totalOutstanding.add(r.expense().amount);
            }
        }

        List<Map<String, Object>> categories = new ArrayList<>();

        byCategory.forEach((category, sums) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("category", category);
            c.put("paid", sums[0]);
            c.put("outstanding", sums[1]);
            categories.add(c);
        });

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month.toString());
        out.put("categories", categories);
        out.put("totalPaid", totalPaid);
        out.put("totalOutstanding", totalOutstanding);
        out.put("items", rows.size());
        out.put("rows", rows.stream().map(r -> r.toMap()).collect(Collectors.toList()));
        return out;
    }

    public List<HomeExpense> list(Long userId) {
        return expenses.findByUserIdAndActiveTrueOrderByCreatedAtAsc(userId);
    }

    /** True when the date is a real due date of this user's expense. */
    public boolean isDueDate(Long userId, Long expenseId, LocalDate due) {
        HomeExpense e = expenses.findByIdAndUserId(expenseId, userId).orElse(null);

        return e != null && !HomeExpenseRules.occurrences(e.frequency, e.startDate, due, due).isEmpty();
    }

    /* ------------------------------------------------------------------ */
    /* Marking paid                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Marks one due date as paid. Does nothing if it was already paid.
     * Returns false if the date is not a real due date of this expense.
     */
    public boolean recordPaid(Long userId, Long expenseId, LocalDate due, String source, String paymentId) {
        HomeExpense e = expenses.findByIdAndUserId(expenseId, userId).orElse(null);

        if (e == null) {
            return false;
        }

        if (HomeExpenseRules.occurrences(e.frequency, e.startDate, due, due).isEmpty()) {
            return false;
        }

        if (paid.findByExpenseIdAndDueDate(e.id, due).isPresent()) {
            return true;
        }

        HomeExpensePayment p = new HomeExpensePayment();
        p.expenseId = e.id;
        p.dueDate = due;
        p.amount = e.amount;
        p.source = source;
        p.paymentId = paymentId;

        paid.save(p);

        return true;
    }

    public boolean unmarkPaid(Long userId, Long expenseId, LocalDate due) {
        HomeExpense e = expenses.findByIdAndUserId(expenseId, userId).orElse(null);

        if (e == null) {
            return false;
        }

        HomeExpensePayment p = paid.findByExpenseIdAndDueDate(e.id, due).orElse(null);

        if (p == null || !"MANUAL".equals(p.source)) {
            return false;   /* a PayPal-confirmed payment cannot be undone here */
        }

        paid.delete(p);

        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Reminders                                                           */
    /* ------------------------------------------------------------------ */

    /** Creates a notification once per upcoming, due or overdue household payment. */
    public int createReminders(Long userId, LocalDate today) {
        int created = 0;

        List<HomeExpense> list = expenses.findByUserIdAndActiveTrueOrderByCreatedAtAsc(userId);

        if (list.isEmpty()) {
            return 0;
        }

        Map<Long, HomeExpense> byId = new HashMap<>();

        list.forEach(e -> byId.put(e.id, e));

        Set<Long> overdueDone = new java.util.HashSet<>();

        for (Row r : actionable(userId, today, 14)) {
            HomeExpense e = r.expense();

            String kind;
            String message;

            if ("OVERDUE".equals(r.status())) {
                /* One overdue reminder per expense per run, so a daily expense does not flood the bell. */
                if (!overdueDone.add(e.id)) {
                    continue;
                }

                kind = "OVERDUE";
                message = e.name + " of " + Inr.of(e.amount) + " was due on " + Inr.date(r.due()) + " and is not marked paid.";

            } else if ("DUE_TODAY".equals(r.status())) {
                kind = "DUE";
                message = e.name + " of " + Inr.of(e.amount) + " is due today.";

            } else if (!r.due().isAfter(today.plusDays(e.remindDaysBefore))) {
                kind = "UPCOMING";
                message = e.name + " of " + Inr.of(e.amount) + " is due on " + Inr.date(r.due()) + ".";

            } else {
                continue;
            }

            if (reminders.existsByExpenseIdAndDueDateAndKind(e.id, r.due(), kind)) {
                continue;
            }

            ReminderSent sent = new ReminderSent();
            sent.expenseId = e.id;
            sent.dueDate = r.due();
            sent.kind = kind;

            try {
                reminders.save(sent);
            } catch (RuntimeException duplicate) {
                continue;   /* another request created it first */
            }

            notifications.save(new Notification(userId, "REMINDER", message, null));

            created++;
        }

        return created;
    }

    private static String text(Object value, int max) {
        if (value == null) {
            return "";
        }

        String s = String.valueOf(value).trim();

        return s.length() > max ? s.substring(0, max) : s;
    }
}
