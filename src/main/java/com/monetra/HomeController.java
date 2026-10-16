package com.monetra;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Home Expense Guardian: recurring household expenses and whether they are paid. */
@RestController
@RequestMapping("/api/home")
public class HomeController {

    private final HomeExpenseService home;
    private final GuardianScoreService score;

    public HomeController(HomeExpenseService home, GuardianScoreService score) {
        this.home = home;
        this.score = score;
    }

    /** Everything the Home Expense screen needs in one call. */
    @GetMapping("/overview")
    public ResponseEntity<?> overview(HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        LocalDate today = LocalDate.now();

        List<Map<String, Object>> due = new ArrayList<>();

        for (HomeExpenseService.Row r : home.actionable(userId, today, 14)) {
            due.add(r.toMap());
        }

        List<Map<String, Object>> all = new ArrayList<>();

        for (HomeExpense e : home.list(userId)) {
            all.add(expenseMap(e));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", home.summaryToday(userId, today));
        out.put("due", due);
        out.put("expenses", all);
        out.put("categories", HomeExpenseRules.CATEGORIES);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/expenses")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        try {
            return ResponseEntity.ok(expenseMap(home.create(userId, body)));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/expenses/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        return home.delete(userId, id)
                ? ResponseEntity.ok(Map.of("deleted", true))
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Expense not found."));
    }

    /** The user says they paid this due date themselves. It is labelled MANUAL, never "verified". */
    @PostMapping("/expenses/{id}/paid")
    public ResponseEntity<?> markPaid(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        LocalDate due;

        try {
            due = LocalDate.parse(String.valueOf(body.get("dueDate")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Choose a valid due date."));
        }

        if (!home.recordPaid(userId, id, due, "MANUAL", null)) {
            return ResponseEntity.badRequest().body(Map.of("error", "That is not a due date of this expense."));
        }

        score.refreshQuietly(userId);

        return ResponseEntity.ok(Map.of("paid", true, "source", "MANUAL"));
    }

    @DeleteMapping("/expenses/{id}/paid")
    public ResponseEntity<?> unmarkPaid(
            @PathVariable Long id,
            @RequestParam String dueDate,
            HttpSession session) {

        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        try {
            if (!home.unmarkPaid(userId, id, LocalDate.parse(dueDate))) {
                return ResponseEntity.badRequest().body(Map.of("error", "Only a payment you marked yourself can be undone."));
            }

            score.refreshQuietly(userId);

            return ResponseEntity.ok(Map.of("unmarked", true));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Choose a valid due date."));
        }
    }

    @GetMapping("/report")
    public ResponseEntity<?> report(@RequestParam(required = false) String month, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        YearMonth ym;

        try {
            ym = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Use a month like 2026-10."));
        }

        return ResponseEntity.ok(home.report(userId, ym, LocalDate.now()));
    }

    private static Map<String, Object> expenseMap(HomeExpense e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id);
        m.put("name", e.name);
        m.put("amount", e.amount);
        m.put("category", e.category);
        m.put("frequency", e.frequency);
        m.put("startDate", e.startDate.toString());
        m.put("recipientPhone", e.recipientPhone);
        m.put("note", e.note);
        m.put("remindDaysBefore", e.remindDaysBefore);
        return m;
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in first."));
    }
}
