package com.monetra;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Guardian Score end to end: the score, badges, the daily update and who may see what. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-score",
                "spring.mail.host=localhost",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class GuardianScoreFlowTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @LocalServerPort
    int port;

    @Autowired
    UserAccountRepository users;

    @Autowired
    GuardianScoreService score;

    @Autowired
    NotificationRepository notifications;

    @Autowired
    GuardianBadgeRepository badges;

    @Autowired
    GuardianPeriodBadgeRepository periods;

    private final LocalDate today = LocalDate.now(INDIA);

    private Long user(String email, String phone) {
        return users.findByEmail(email)
                .orElseGet(() -> users.save(new UserAccount(email, new BCryptPasswordEncoder().encode("password-s"), phone, email)))
                .getId();
    }

    private long count(Long userId, String type) {
        return notifications.findTop30ByUserIdOrderByCreatedAtDesc(userId).stream().filter(n -> type.equals(n.type)).count();
    }

    /** ₹50,000 balance, a ₹1,000 daily limit kept today, one EMI in ten days and one goal on plan. */
    private String records(String extraLoan) {
        return "{\"limits\":{\"daily\":1000},"
                + "\"expenses\":[{\"id\":1,\"amount\":500,\"date\":\"" + today + "\"}],"
                + "\"profile\":{\"balance\":50000,\"balanceAt\":" + System.currentTimeMillis() + "},"
                + "\"loans\":[{\"id\":11,\"name\":\"Bike EMI\",\"amount\":2000,\"due\":\"" + today.plusDays(10) + "\"}" + extraLoan + "],"
                + "\"goals\":[{\"id\":21,\"name\":\"Laptop\",\"target\":60000,\"saved\":20000,\"monthly\":5000,"
                + "\"targetDate\":\"" + today.plusMonths(10) + "\"}]}";
    }

    @BeforeEach
    void setUp() {
        user("sara@test.dev", "9000000081");
        user("omar@test.dev", "9000000082");
    }

    @Test
    void noDataMeansNoScoreAndNoBadge() throws Exception {
        Long id = user("nina@test.dev", "9000000083");
        Http nina = Http.login(port, "nina@test.dev", "password-s");

        String body = nina.get("/api/guardian/score").body();

        assertTrue(body.contains("\"available\":false"), body);
        assertTrue(body.contains("\"score\":null"), body);
        assertTrue(body.contains("\"confidence\":\"INSUFFICIENT_DATA\""), body);
        assertTrue(body.contains("\"anyBadge\":false"), body);
        assertTrue(badges.findByUserIdOrderByEarnedAtAsc(id).isEmpty());

        /* And no daily update is made up for it, even at midday. */
        Instant noon = ZonedDateTime.of(today, LocalTime.NOON, INDIA).toInstant();

        assertFalse(score.sendDailyIfDue(id, noon));
        assertEquals(0, count(id, "SCORE_DAILY"));
    }

    @Test
    void theScoreIsTheSumOfItsPartsAndBadgesAreEarnedOnce() throws Exception {
        Long id = user("sara@test.dev", "9000000081");
        Http sara = Http.login(port, "sara@test.dev", "password-s");

        assertEquals(200, sara.put("/api/data", records("")).statusCode());

        String body = sara.get("/api/guardian/score").body();

        /* Daily 25 (kept today), EMI 25 (balance covers it), bills 0 (none recorded), savings 20 (on plan), risk 10. */
        assertTrue(body.contains("\"score\":80"), body);
        assertTrue(body.contains("\"id\":\"DAILY\",\"name\":\"Daily Spending Discipline\",\"earned\":25,\"max\":25"), body);
        assertTrue(body.contains("\"id\":\"BILLS\",\"name\":\"Essential Bills Management\",\"earned\":0,\"max\":20"), body);
        assertTrue(body.contains("\"level\":{\"id\":\"CHAMPION\",\"title\":\"Guardian Champion\""), body);
        assertTrue(body.contains("\"confidence\":\"MANUAL_DATA\""), body);
        assertTrue(body.contains("\"low\":[\"BILLS\""), "the lowest part comes first: " + body);
        assertTrue(body.contains("Add your regular bills"), "tips come from the real records: " + body);

        /* Crossing four levels at once records four badges and sends one notification. */
        assertEquals(4, badges.findByUserIdOrderByEarnedAtAsc(id).size());
        assertEquals(1, count(id, "BADGE_EARNED"));

        /* Asking again changes nothing. */
        sara.get("/api/guardian/score");
        sara.post("/api/guardian/score/refresh", "{}");

        assertEquals(4, badges.findByUserIdOrderByEarnedAtAsc(id).size());
        assertEquals(1, count(id, "BADGE_EARNED"));

        /* An overdue EMI pulls the score down: the current level drops, the highest stays, nothing new is sent. */
        assertEquals(200, sara.put("/api/data", records(",{\"id\":12,\"name\":\"Phone EMI\",\"amount\":1500,\"due\":\""
                + today.minusDays(3) + "\"}")).statusCode());

        String lower = sara.get("/api/guardian/score").body();

        assertTrue(lower.contains("\"score\":55"), lower);
        assertTrue(lower.contains("\"level\":{\"id\":\"BUILDER\""), lower);
        assertTrue(lower.contains("\"highestLevel\":{\"id\":\"CHAMPION\""), lower);
        assertTrue(lower.contains("\"id\":\"BUILDER\",\"title\":\"Guardian Builder\",\"badge\":\"Builder Badge\",\"min\":40,\"max\":59,\"status\":\"CURRENT\""), lower);
        assertTrue(lower.contains("\"id\":\"CHAMPION\",\"title\":\"Guardian Champion\",\"badge\":\"Champion Badge\",\"min\":80,\"max\":94,\"status\":\"EARNED\""), lower);
        assertTrue(lower.contains("\"id\":\"ELITE\",\"title\":\"Guardian Elite\",\"badge\":\"Elite Badge\",\"min\":95,\"max\":100,\"status\":\"NOT_EARNED\""), lower);
        assertTrue(lower.contains("Pay the overdue EMI \\\"Phone EMI\\\""), "the overdue EMI is the first tip: " + lower);
        assertEquals(1, count(id, "BADGE_EARNED"));

        /* The score history keeps one row a day. */
        String history = sara.get("/api/guardian/score/history").body();

        assertEquals(1, history.split("\"day\"", -1).length - 1, history);
    }

    @Test
    void oneDailyUpdatePerLocalDayFromNineOClock() throws Exception {
        Long id = user("omar@test.dev", "9000000082");
        Http omar = Http.login(port, "omar@test.dev", "password-s");

        assertEquals(200, omar.put("/api/data", records("")).statusCode());

        Instant early = ZonedDateTime.of(today, LocalTime.of(8, 30), INDIA).toInstant();
        Instant morning = ZonedDateTime.of(today, LocalTime.of(10, 0), INDIA).toInstant();
        Instant later = ZonedDateTime.of(today, LocalTime.of(22, 0), INDIA).toInstant();
        Instant tomorrow = ZonedDateTime.of(today.plusDays(1), LocalTime.of(9, 15), INDIA).toInstant();

        assertFalse(score.sendDailyIfDue(id, early), "not before 9 in the morning");
        assertTrue(score.sendDailyIfDue(id, morning));
        assertFalse(score.sendDailyIfDue(id, later), "only once a day, however often the app is opened");

        /* The notification list itself never adds a second one. */
        omar.get("/api/notifications");
        omar.get("/api/notifications");

        assertEquals(1, count(id, "SCORE_DAILY"));

        String list = omar.get("/api/notifications").body();

        assertTrue(list.contains("\"type\":\"SCORE_DAILY\",\"message\":\"Your Guardian Score is 80/100, Guardian Champion. Tip: "), list);

        assertTrue(score.sendDailyIfDue(id, tomorrow), "a new day gets a new update");
        assertEquals(2, count(id, "SCORE_DAILY"));
    }

    @Test
    void theDailyUpdateFollowsTheUsersOwnTimeZoneAndCanBeSwitchedOff() throws Exception {
        Long id = user("lena@test.dev", "9000000084");
        Http lena = Http.login(port, "lena@test.dev", "password-s");

        assertEquals(200, lena.put("/api/data", records("")).statusCode());

        /* An unknown zone is refused; a real one is kept. */
        assertEquals(400, lena.post("/api/guardian/score/refresh", "{\"timezone\":\"Mars/Base\"}").statusCode());
        assertEquals(200, lena.post("/api/guardian/score/refresh", "{\"timezone\":\"America/New_York\"}").statusCode());

        /* 10:30 in India is 01:00 in New York: too early there. */
        Instant indiaMorning = ZonedDateTime.of(today, LocalTime.of(10, 30), INDIA).toInstant();

        assertFalse(score.sendDailyIfDue(id, indiaMorning));

        Instant newYorkMorning = ZonedDateTime.of(today, LocalTime.of(10, 0), ZoneId.of("America/New_York")).toInstant();

        assertTrue(score.sendDailyIfDue(id, newYorkMorning));

        /* Switched off: nothing more. */
        assertEquals(200, lena.put("/api/data", records("").replace("\"profile\":{",
                "\"profile\":{\"notify\":{\"scoreDaily\":false},")).statusCode());

        Instant nextMorning = ZonedDateTime.of(today.plusDays(1), LocalTime.of(10, 0), ZoneId.of("America/New_York")).toInstant();

        assertFalse(score.sendDailyIfDue(id, nextMorning));
        assertEquals(1, count(id, "SCORE_DAILY"));
    }

    @Test
    void manyRunsAtOnceStillMakeOneDailyUpdate() throws Exception {
        Long id = user("ravi.score@test.dev", "9000000085");
        Http ravi = Http.login(port, "ravi.score@test.dev", "password-s");

        assertEquals(200, ravi.put("/api/data", records("")).statusCode());

        Instant morning = ZonedDateTime.of(today, LocalTime.of(11, 0), INDIA).toInstant();

        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Boolean>> runs = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            Callable<Boolean> run = () -> score.sendDailyIfDue(id, morning);
            runs.add(pool.submit(run));
        }

        int made = 0;

        for (Future<Boolean> f : runs) {
            if (f.get()) {
                made++;
            }
        }

        pool.shutdown();

        assertEquals(1, made);
        assertEquals(1, count(id, "SCORE_DAILY"));
        assertEquals(1, count(id, "BADGE_EARNED"));
    }

    @Test
    void eachUserSeesOnlyTheirOwnScoreAndNotifications() throws Exception {
        Long saraId = user("sara@test.dev", "9000000081");
        Http sara = Http.login(port, "sara@test.dev", "password-s");
        Http omar = Http.login(port, "omar@test.dev", "password-s");

        assertEquals(200, sara.put("/api/data", records("")).statusCode());
        sara.get("/api/guardian/score");

        assertTrue(count(saraId, "BADGE_EARNED") >= 1);
        assertFalse(omar.get("/api/notifications").body().contains("badge"), "badges belong to their owner");

        /* Not logged in: nothing. */
        assertEquals(401, new Http(port).get("/api/guardian/score").statusCode());
        assertEquals(401, new Http(port).get("/api/guardian/score/history").statusCode());
        assertEquals(401, new Http(port).post("/api/guardian/score/refresh", "{}").statusCode());
    }

    /* ---------------- daily and weekly badges ---------------- */

    /** A ₹1,000 daily limit, these expenses (date, amount, date, amount...) and, when wanted, badge notifications off. */
    private static String spending(boolean badgeNotes, Object... dayAndAmount) {
        StringBuilder expenses = new StringBuilder();

        for (int i = 0; i < dayAndAmount.length; i += 2) {
            expenses.append(i == 0 ? "" : ",").append("{\"id\":").append(i + 1).append(",\"amount\":").append(dayAndAmount[i + 1])
                    .append(",\"date\":\"").append(dayAndAmount[i]).append("\"}");
        }

        return "{\"limits\":{\"daily\":1000},\"expenses\":[" + expenses + "]"
                + (badgeNotes ? "" : ",\"profile\":{\"notify\":{\"badges\":false}}") + "}";
    }

    @Test
    void eachFinishedDayIsDecidedOnceAndOnlyTheDayJustOverIsTold() throws Exception {
        Long id = user("dina@test.dev", "9000000086");
        Http dina = Http.login(port, "dina@test.dev", "password-s");

        assertEquals(200, dina.put("/api/data", spending(true, today, 500, today.plusDays(1), 1500)).statusCode());

        /* While the day is still going on, nothing is decided. */
        dina.get("/api/guardian/score");

        assertTrue(periods.findByUserIdAndKindAndStartGreaterThanEqualOrderByStartAsc(id, GuardianPeriodBadge.DAY, today).isEmpty());

        /* The next morning: 500 of a 1,000 limit was a Good Day, told once however often it is asked. */
        Instant nextMorning = ZonedDateTime.of(today.plusDays(1), LocalTime.of(7, 0), INDIA).toInstant();

        score.decidePeriods(id, nextMorning);
        score.decidePeriods(id, nextMorning);

        assertEquals(1, periods.countByUserIdAndKindAndEarnedTrue(id, GuardianPeriodBadge.DAY));
        assertEquals(1, count(id, "DAY_BADGE"));

        /* The morning after: 1,500 over a 1,000 limit is recorded too, without a badge or a notification. */
        score.decidePeriods(id, ZonedDateTime.of(today.plusDays(2), LocalTime.of(7, 0), INDIA).toInstant());

        List<GuardianPeriodBadge> days = periods.findByUserIdAndKindAndStartGreaterThanEqualOrderByStartAsc(id, GuardianPeriodBadge.DAY, today);

        assertEquals(2, days.size());
        assertTrue(days.get(0).earned);
        assertFalse(days.get(1).earned);
        assertEquals(0, new java.math.BigDecimal("1500").compareTo(days.get(1).spent));
        assertEquals(1, count(id, "DAY_BADGE"));

        /* On the notification screen it is on the badge side, not with the payments. */
        String list = dina.get("/api/notifications").body();
        int split = list.indexOf("\"scoreItems\"");

        assertTrue(split > 0, list);
        assertFalse(list.substring(0, split).contains("DAY_BADGE"), list);
        assertTrue(list.substring(split).contains("\"type\":\"DAY_BADGE\",\"message\":\"You earned the Good Day badge for "), list);
    }

    @Test
    void aWeekWithFiveGoodDaysEarnsTheGoodWeekBadge() throws Exception {
        Long id = user("ella@test.dev", "9000000087");
        Http ella = Http.login(port, "ella@test.dev", "password-s");

        LocalDate monday = GuardianScoreService.weekStart(today).plusWeeks(1);

        assertEquals(200, ella.put("/api/data", spending(true, monday.plusDays(1), 2000, monday.plusDays(3), 2000)).statusCode());

        /* The Monday after that week: its seven days and the week itself are decided, once. */
        Instant after = ZonedDateTime.of(monday.plusWeeks(1), LocalTime.of(7, 0), INDIA).toInstant();

        score.decidePeriods(id, after);
        score.decidePeriods(id, after);

        assertEquals(5, periods.countByUserIdAndKindAndEarnedTrue(id, GuardianPeriodBadge.DAY));
        assertEquals(1, periods.countByUserIdAndKindAndEarnedTrue(id, GuardianPeriodBadge.WEEK));
        assertEquals(1, count(id, "WEEK_BADGE"));
        assertEquals(1, count(id, "DAY_BADGE"), "only the day just over is told, not the days caught up on");

        String body = ella.get("/api/guardian/score").body();

        assertTrue(body.contains("\"goodDaysTotal\":5,\"goodWeeksTotal\":1"), body);
        assertTrue(body.contains("\"start\":\"" + monday + "\",\"end\":\"" + monday.plusDays(6)
                + "\",\"goodDays\":5,\"days\":7,\"earned\":true"), body);
    }

    @Test
    void aWeekWithFourGoodDaysIsMissedAndNothingIsSentWhenBadgesAreOff() throws Exception {
        Long id = user("fay@test.dev", "9000000088");
        Http fay = Http.login(port, "fay@test.dev", "password-s");

        LocalDate monday = GuardianScoreService.weekStart(today).plusWeeks(1);

        assertEquals(200, fay.put("/api/data", spending(false,
                monday, 1200, monday.plusDays(2), 1200, monday.plusDays(4), 1200)).statusCode());

        score.decidePeriods(id, ZonedDateTime.of(monday.plusWeeks(1), LocalTime.of(7, 0), INDIA).toInstant());

        List<GuardianPeriodBadge> weeks = periods.findByUserIdAndKindAndStartGreaterThanEqualOrderByStartAsc(id, GuardianPeriodBadge.WEEK, monday);

        assertEquals(1, weeks.size());
        assertFalse(weeks.get(0).earned);
        assertEquals(4, weeks.get(0).goodDays);
        assertEquals(4, periods.countByUserIdAndKindAndEarnedTrue(id, GuardianPeriodBadge.DAY), "the days are still recorded");
        assertEquals(0, count(id, "DAY_BADGE"));
        assertEquals(0, count(id, "WEEK_BADGE"));
    }

    @Test
    void theWeekTheUserJoinedCountsOnlyWhenTheyJoinedOnItsMonday() throws Exception {
        Long id = user("gita.week@test.dev", "9000000089");
        Http gita = Http.login(port, "gita.week@test.dev", "password-s");

        assertEquals(200, gita.put("/api/data", spending(true)).statusCode());

        LocalDate monday = GuardianScoreService.weekStart(today);

        score.decidePeriods(id, ZonedDateTime.of(monday.plusWeeks(1), LocalTime.of(7, 0), INDIA).toInstant());

        /* Joining later in the week is no missed week: it is simply not counted. */
        assertEquals(today.equals(monday) ? 1 : 0,
                periods.findByUserIdAndKindAndStartGreaterThanEqualOrderByStartAsc(id, GuardianPeriodBadge.WEEK, monday).size());
    }
}
