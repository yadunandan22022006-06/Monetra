package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Guardian Score's rules on their own: levels, the five parts, sorting. No server needed. */
class GuardianScoreRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private final ObjectMapper mapper = new ObjectMapper();

    /* ---------------- levels and badges ---------------- */

    @Test
    void everyBoundaryScoreGetsTheRightLevel() {
        int[][] cases = {{0, 0}, {39, 0}, {40, 1}, {59, 1}, {60, 2}, {79, 2}, {80, 3}, {94, 3}, {95, 4}, {100, 4}};

        for (int[] c : cases) {
            assertEquals(GuardianScoreService.Level.values()[c[1]], GuardianScoreService.Level.of(c[0]), "score " + c[0]);
        }

        assertEquals("Guardian Protector", GuardianScoreService.Level.PROTECTOR.title);
        assertEquals("Protector Badge", GuardianScoreService.Level.PROTECTOR.badge);
        assertEquals(79, GuardianScoreService.Level.PROTECTOR.max());
        assertEquals(100, GuardianScoreService.Level.ELITE.max());
    }

    @Test
    void aPartNeverGoesBelowZeroOrAboveItsMaximum() {
        assertEquals(25, new GuardianScoreService.Category("DAILY", "d", 30, 25, "", null, null, 9).earned());
        assertEquals(0, new GuardianScoreService.Category("RISK", "r", -6, 10, "", null, null, 9).earned());
    }

    /* ---------------- sorting ---------------- */

    private static GuardianScoreService.Category cat(String id, int earned, int max) {
        return new GuardianScoreService.Category(id, id, earned, max, "", null, null, 9);
    }

    @Test
    void sortingUsesTheShareOfPointsThenThePointsThenAFixedOrder() {
        /* Daily, EMI and Bills are all at 80%; Daily and EMI both have 20 points. */
        List<GuardianScoreService.Category> parts = List.of(
                cat("DAILY", 20, 25), cat("EMI", 20, 25), cat("BILLS", 16, 20), cat("SAVINGS", 10, 20), cat("RISK", 10, 10));

        assertEquals(List.of("RISK", "DAILY", "EMI", "BILLS", "SAVINGS"),
                GuardianScoreService.sorted(parts, true).stream().map(c -> c.id()).toList());

        assertEquals(List.of("SAVINGS", "BILLS", "DAILY", "EMI", "RISK"),
                GuardianScoreService.sorted(parts, false).stream().map(c -> c.id()).toList());

        /* Sorting never changes a score. */
        assertEquals(76, GuardianScoreService.sorted(parts, true).stream().mapToInt(c -> c.earned()).sum());
        assertEquals("DAILY", parts.get(0).id());
    }

    /* ---------------- the five parts ---------------- */

    @Test
    void dailyDisciplineCountsTheLastSevenDays() throws Exception {
        JsonNode data = mapper.readTree("{\"limits\":{\"daily\":100},\"expenses\":["
                + "{\"amount\":50,\"date\":\"" + TODAY.minusDays(6) + "\"},"
                + "{\"amount\":110,\"date\":\"" + TODAY.minusDays(5) + "\"},"
                + "{\"amount\":200,\"date\":\"" + TODAY.minusDays(4) + "\"},"
                + "{\"amount\":900,\"date\":\"" + TODAY.minusDays(3) + "\",\"source\":\"emi\"}]}");

        /* 5 days within (2 each), one up to a quarter over (1), one far over (0): 11 of 14 halves. EMIs never count. */
        GuardianScoreService.Category c = GuardianScoreService.daily(data, TODAY, null);

        assertEquals(20, c.earned());
        assertEquals(25, c.max());
        assertTrue(c.explanation().contains("on 5 of the last 7 days"), c.explanation());

        /* A user who joined yesterday is judged on 2 days, not on days before they joined. */
        GuardianScoreService.Category young = GuardianScoreService.daily(data, TODAY, TODAY.minusDays(1));

        assertEquals(25, young.earned());
        assertTrue(young.explanation().contains("of the last 2 days"), young.explanation());

        /* No limit: missing data earns nothing. */
        GuardianScoreService.Category none = GuardianScoreService.daily(mapper.readTree("{}"), TODAY, null);

        assertEquals(0, none.earned());
        assertTrue(none.advice().contains("Set a daily limit"));
    }

    @Test
    void emiPreparednessNeedsAKnownBalanceAndScoresNothingWhenOverdue() {
        Map<String, Object> noLoans = new LinkedHashMap<>(Map.of("count", 0));

        assertEquals(25, GuardianScoreService.emi(noLoans, true).earned());
        assertEquals(0, GuardianScoreService.emi(noLoans, false).earned(), "no balance: nothing can be checked");

        Map<String, Object> covered = new LinkedHashMap<>();
        covered.put("count", 1);
        covered.put("status", "LOW");
        covered.put("calendar", List.of(Map.of("name", "Bike", "date", TODAY.plusDays(5).toString(), "overdue", false)));

        assertEquals(25, GuardianScoreService.emi(covered, true).earned());
        assertEquals(0, GuardianScoreService.emi(covered, false).earned());

        covered.put("status", "HIGH");
        assertEquals(5, GuardianScoreService.emi(covered, true).earned());

        covered.put("status", "MEDIUM");
        assertEquals(15, GuardianScoreService.emi(covered, true).earned());

        Map<String, Object> overdue = new LinkedHashMap<>(covered);
        overdue.put("calendar", List.of(Map.of("name", "Bike", "date", TODAY.minusDays(2).toString(), "overdue", true)));

        GuardianScoreService.Category c = GuardianScoreService.emi(overdue, true);

        assertEquals(0, c.earned());
        assertEquals(1, c.priority(), "an overdue EMI is the most important tip");
        assertTrue(c.advice().contains("Pay the overdue EMI \"Bike\""), c.advice());
    }

    private static HomeExpenseService.Row bill(String name, String status) {
        HomeExpense e = new HomeExpense();
        e.name = name;
        return new HomeExpenseService.Row(e, TODAY.minusDays(3), status, null, null);
    }

    @Test
    void billsCountWhatIsOverdue() {
        assertEquals(0, GuardianScoreService.bills(false, List.of()).earned(), "no bills recorded earns nothing");
        assertEquals(20, GuardianScoreService.bills(true, List.of()).earned());

        List<HomeExpenseService.Row> due = List.of(bill("Rent", "PAID"), bill("Power", "OVERDUE"), bill("Milk", "DUE_TODAY"), bill("Gas", "PAID"));

        GuardianScoreService.Category c = GuardianScoreService.bills(true, due);

        assertEquals(15, c.earned());
        assertEquals(5, c.gain(), "paying the one overdue bill would give back 5 points");
        assertTrue(c.advice().contains("\"Power\""), c.advice());
    }

    private static Map<String, Object> goal(String name, String status, String saved, String should, String shortfall) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("name", name);
        g.put("status", status);
        g.put("saved", new BigDecimal(saved));

        if (should != null) {
            g.put("shouldHaveSaved", new BigDecimal(should));
            g.put("shortfall", new BigDecimal(shortfall));
        }

        return g;
    }

    @Test
    void savingsSuggestOnlyWhatFitsInTodaysSafeMoney() {
        assertEquals(0, GuardianScoreService.savings(List.of(), null).earned());

        List<Map<String, Object>> goals = List.of(
                goal("Bike", "BEHIND", "1000", "4000", "3000"),
                goal("Phone", "ON_TRACK", "500", null, null));

        /* Bike is a quarter of the way to its plan, Phone is on plan: 0.625 of 20 points. */
        GuardianScoreService.Category c = GuardianScoreService.savings(goals, new BigDecimal("1000"));

        assertEquals(13, c.earned());
        assertTrue(c.advice().contains("₹1,000.00"), "never more than today's safe money: " + c.advice());
        assertEquals(2, c.gain());

        /* No safe money known: no amount is suggested. */
        GuardianScoreService.Category unsafe = GuardianScoreService.savings(goals, null);

        assertNull(unsafe.gain());
        assertTrue(unsafe.advice().contains("once your EMIs and bills are covered"), unsafe.advice());
    }

    private static PaymentRecord payment(String status, String flags, Instant at) {
        PaymentRecord p = new PaymentRecord();
        p.status = status;
        p.riskFlags = flags;
        p.createdAt = at.toString();
        return p;
    }

    @Test
    void onlyPaymentsPaypalConfirmedCountForRisk() {
        Instant now = Instant.parse("2026-10-16T06:00:00Z");

        List<PaymentRecord> mine = new ArrayList<>(List.of(
                payment("COMPLETED", "New payee", now.minus(2, ChronoUnit.DAYS)),
                payment("COMPLETED", "Unusual amount", now.minus(5, ChronoUnit.DAYS)),
                payment("COMPLETED", "Old one", now.minus(40, ChronoUnit.DAYS)),
                payment("AWAITING_APPROVAL", "Not confirmed", now.minus(1, ChronoUnit.DAYS)),
                payment("COMPLETED", null, now.minus(1, ChronoUnit.DAYS))));

        assertEquals(2, GuardianScoreService.risk(mine, now).earned());
        assertEquals(10, GuardianScoreService.risk(List.of(), now).earned());
    }

    /* ---------------- daily and weekly badges ---------------- */

    @Test
    void aGoodDayIsWithinTheLimitAndAGoodWeekHasFiveOfThem() {
        BigDecimal limit = new BigDecimal("100");

        assertTrue(GuardianScoreService.goodDay(new BigDecimal("100"), limit), "exactly at the limit still counts");
        assertTrue(GuardianScoreService.goodDay(BigDecimal.ZERO, limit));
        assertFalse(GuardianScoreService.goodDay(new BigDecimal("100.01"), limit));
        assertFalse(GuardianScoreService.goodDay(BigDecimal.ZERO, BigDecimal.ZERO), "no limit, no badge");

        assertFalse(GuardianScoreService.goodWeek(4));
        assertTrue(GuardianScoreService.goodWeek(5));
        assertTrue(GuardianScoreService.goodWeek(7));

        /* Weeks run Monday to Sunday: 16 Oct 2026 is a Friday. */
        assertEquals(LocalDate.of(2026, 10, 12), GuardianScoreService.weekStart(TODAY));
        assertEquals(LocalDate.of(2026, 10, 12), GuardianScoreService.weekStart(LocalDate.of(2026, 10, 12)));
        assertEquals(LocalDate.of(2026, 10, 12), GuardianScoreService.weekStart(LocalDate.of(2026, 10, 18)));
    }
}
