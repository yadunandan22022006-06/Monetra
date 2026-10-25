package com.monetra;

import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Putting the Try Demo account back to sample data. Real people (signed up the real way, with an email code) and
 * the other demo account get rows in every table, then the reset runs, and every one of their rows must be exactly
 * as before. The PayPal payment records are never touched, the demo account's own included.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-demo-reset",
                "spring.mail.host=localhost",
                "spring.mail.username=",
                "monetra.log-otp=true",
                "monetra.seed-demo-users=true",
                "monetra.demo-login=true",
                "monetra.demo-password=Test-Demo-Password-1",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class DemoResetTest {

    private static final String DEMO_PASSWORD = "Test-Demo-Password-1";
    private static final String REAL_PASSWORD = "Real-User-Pass-1";

    /* Written out again here on purpose: the test must not borrow the list it is checking. */
    private static final List<String> OWNED_BY_USER = List.of(
            "HomeExpense", "HePayment", "Notification", "Contact", "BankAccount", "BankLink",
            "GuardianAlert", "GuardianBadge", "GuardianPeriodBadge", "GuardianScoreRecord", "GuardianScoreState",
            "ScamReport");

    private static final List<String> OWNED_BY_EXPENSE = List.of("HomeExpensePayment", "ReminderSent");

    private static final AtomicInteger NEXT = new AtomicInteger(60);

    @LocalServerPort
    int port;

    @Autowired
    UserAccountRepository users;

    @Autowired
    UserDataRepository store;

    @Autowired
    DemoReset reset;

    @Autowired
    EntityManager em;

    @Autowired
    PlatformTransactionManager transactions;

    private TransactionTemplate tx;

    @BeforeEach
    void startFromCleanDemoData() {
        tx = new TransactionTemplate(transactions);

        reset.resetDemoAccount();
    }

    /* ------------------------------------------------------------------ */

    @Test
    void onlyTheDemoAccountGoesBackToSampleDataAndEveryoneElseKeepsEverything(CapturedOutput output) throws Exception {
        Long demo1 = idOf("demo1@monetra.test");
        Long demo2 = idOf("demo2@monetra.test");

        /* Two real people, signed up the real way with an email code. */
        Long realA = signUp(output, "reset-a@test.dev");
        Long realB = signUp(output, "reset-b@test.dev");

        long demo1Expense = fill(demo1, "demo-old");
        long demo2Expense = fill(demo2, "two");
        long aExpense = fill(realA, "A");
        long bExpense = fill(realB, "B");

        /* PayPal payment records: the demo account's own, one it sent to a real person, one a real person sent to it. */
        addPayment("pay-demo-own", demo1, null, "COMPLETED");
        addPayment("pay-demo-to-a", demo1, realA, "COMPLETED");
        addPayment("pay-a-to-demo", realA, demo1, "COMPLETED");
        addPayment("pay-a-own", realA, null, "AWAITING_APPROVAL");
        addPayment("pay-b-own", realB, null, "COMPLETED");

        Map<String, Long> demo1Before = counts(demo1, demo1Expense);
        assertTrue(demo1Before.values().stream().allMatch(n -> n == 1L), "the demo account has a row in every table: " + demo1Before);

        Map<String, Long> demo2Before = counts(demo2, demo2Expense);
        Map<String, Long> aBefore = counts(realA, aExpense);
        Map<String, Long> bBefore = counts(realB, bExpense);

        String demo2Records = recordsOf(demo2);
        String aRecords = recordsOf(realA);
        String bRecords = recordsOf(realB);

        List<String> paymentsBefore = allPayments();
        Map<String, String> accountsBefore = accounts();
        Map<String, Long> totalsBefore = totals();

        DemoReset.Result result = reset.resetDemoAccount();

        /* What it did: the demo account's rows went, and it reports the PayPal ones it left alone. */
        Map<String, Long> removed = new TreeMap<>();
        result.removed().forEach((table, n) -> removed.put(table, n.longValue()));

        assertTrue(result.done());
        assertEquals(demo1Before, removed, "one row removed from each table");
        assertEquals(2, result.paymentsKept());

        /* The demo account: nothing left of the old rows, its records are the sample again. */
        assertTrue(counts(demo1, demo1Expense).values().stream().allMatch(n -> n == 0L), counts(demo1, demo1Expense).toString());
        assertEquals(DemoData.sampleRecords(), recordsOf(demo1));

        /* Everybody else: every row, every record, exactly as before. */
        assertEquals(demo2Before, counts(demo2, demo2Expense));
        assertEquals(aBefore, counts(realA, aExpense));
        assertEquals(bBefore, counts(realB, bExpense));

        assertEquals(demo2Records, recordsOf(demo2));
        assertEquals(aRecords, recordsOf(realA));
        assertEquals(bRecords, recordsOf(realB));

        /* PayPal payment records: the whole table is identical, the demo account's own rows included. */
        assertEquals(paymentsBefore, allPayments());

        /* The accounts (emails, password hashes, phones) are identical, and nothing was added or lost anywhere else. */
        assertEquals(accountsBefore, accounts());

        Map<String, Long> totalsAfter = totals();

        for (String table : totalsBefore.keySet()) {
            long lost = OWNED_BY_USER.contains(table) || OWNED_BY_EXPENSE.contains(table) ? 1 : 0;

            assertEquals(totalsBefore.get(table) - lost, totalsAfter.get(table), table);
        }

        /* Signing in still works for everyone: the real people with their own data, the demo account with the sample. */
        Http a = Http.login(port, "reset-a@test.dev", REAL_PASSWORD);
        assertTrue(a.get("/api/data").body().contains("\"marker\":\"A\""));

        Http b = Http.login(port, "reset-b@test.dev", REAL_PASSWORD);
        assertTrue(b.get("/api/data").body().contains("\"marker\":\"B\""));

        Http demo = Http.login(port, "demo1@monetra.test", DEMO_PASSWORD);
        String sample = demo.get("/api/data").body();
        assertTrue(sample.contains("Laptop EMI") && sample.contains("New phone"), sample);
        assertFalse(sample.contains("demo-old"), sample);

        Http tryDemo = new Http(port);
        assertEquals(200, tryDemo.post("/api/auth/demo", "").statusCode());
        assertTrue(tryDemo.get("/api/data").body().contains("Laptop EMI"));

        /* Email-code sign-up still works after the reset, and the new person signs in. */
        signUp(output, "reset-c@test.dev");
        assertTrue(Http.login(port, "reset-c@test.dev", REAL_PASSWORD).get("/api/auth/me").body().contains("reset-c@test.dev"));
    }

    @Test
    void runningItAgainChangesNothingFurther() {
        Long demo1 = idOf("demo1@monetra.test");
        long expense = fill(demo1, "again");

        assertTrue(reset.resetDemoAccount().done());

        DemoReset.Result second = reset.resetDemoAccount();

        assertTrue(second.done());
        assertTrue(second.removed().values().stream().allMatch(n -> n == 0), second.removed().toString());
        assertTrue(counts(demo1, expense).values().stream().allMatch(n -> n == 0L));
        assertEquals(DemoData.sampleRecords(), recordsOf(demo1));
    }

    @Test
    void withNoDemoAccountItDoesNothingAtAll() {
        UserAccountRepository noUsers = mock(UserAccountRepository.class);
        UserDataRepository store = mock(UserDataRepository.class);
        PaymentRepository payments = mock(PaymentRepository.class);
        EntityManager manager = mock(EntityManager.class);

        when(noUsers.findByEmail("demo1@monetra.test")).thenReturn(Optional.empty());

        DemoReset.Result result = new DemoReset(noUsers, store, payments, manager).resetDemoAccount();

        assertFalse(result.done());

        verifyNoInteractions(store, payments, manager);
    }

    @Test
    void theStartUpSwitchDecidesWhetherItRuns() throws Exception {
        Long demo1 = idOf("demo1@monetra.test");

        tx.executeWithoutResult(s -> em.persist(new Notification(demo1, "INFO", "old note", null)));

        /* Switch off (the normal case): starting the server leaves the demo account's data alone. */
        new DemoData(users, store, DEMO_PASSWORD, reset, false).run(null);

        assertEquals(1L, count("Notification", demo1));

        /* Switch on: starting the server puts the demo account back to the sample. */
        new DemoData(users, store, DEMO_PASSWORD, reset, true).run(null);

        assertEquals(0L, count("Notification", demo1));
        assertEquals(DemoData.sampleRecords(), recordsOf(demo1));
    }

    @Test
    void everyTableThatBelongsToAUserIsEitherResetOrOnPurposeLeftAlone() {
        Set<String> leftAlone = Set.of("PaymentRecord", "UserAccount", "UserData");

        for (EntityType<?> entity : em.getMetamodel().getEntities()) {
            String name = entity.getName();

            boolean belongsToAUser = entity.getAttributes().stream()
                    .anyMatch(a -> a.getName().equals("userId") || a.getName().equals("expenseId"));

            if (belongsToAUser && !leftAlone.contains(name)) {
                assertTrue(DemoReset.OWNED.containsKey(name), name + " holds a user's rows but the demo reset does not cover it");
            }
        }

        /* The PayPal payments and the accounts themselves are never in the reset. */
        assertFalse(DemoReset.OWNED.containsKey("PaymentRecord"));
        assertFalse(DemoReset.OWNED.containsKey("UserAccount"));

        /* Every delete is tied to the one id that is passed in. */
        for (String delete : DemoReset.OWNED.values()) {
            assertTrue(delete.startsWith("delete from ") && delete.contains(":id"), delete);
        }
    }

    /* ------------------------------------------------------------------ */

    private Long idOf(String email) {
        return users.findByEmail(email).orElseThrow().getId();
    }

    /** The code the server wrote to its log for this email. */
    private static String codeFor(CapturedOutput output, String email) {
        Matcher m = Pattern.compile("The OTP for " + Pattern.quote(email) + " is (\\d{6})").matcher(output.getAll());

        String last = null;

        while (m.find()) {
            last = m.group(1);
        }

        assertTrue(last != null, "the server logged a code for " + email);

        return last;
    }

    private Long signUp(CapturedOutput output, String email) throws Exception {
        Http http = new Http(port);

        String phone = String.format("90000%05d", NEXT.incrementAndGet());

        assertEquals(200, http.post("/api/auth/send-otp",
                "{\"email\":\"" + email + "\",\"password\":\"" + REAL_PASSWORD + "\",\"phone\":\"" + phone + "\",\"name\":\"Real Person\"}").statusCode());

        assertEquals(200, http.post("/api/auth/verify-otp",
                "{\"email\":\"" + email + "\",\"otp\":\"" + codeFor(output, email) + "\"}").statusCode());

        return idOf(email);
    }

    /** One row in every table for this person, and records that say who they belong to. Returns their home expense id. */
    private long fill(Long userId, String tag) {
        return tx.execute(status -> {
            LocalDate today = LocalDate.now();

            HomeExpense home = new HomeExpense();
            home.userId = userId;
            home.name = "Rent " + tag;
            home.amount = new BigDecimal("1000.00");
            home.frequency = "MONTHLY";
            home.startDate = today;
            em.persist(home);

            HomeExpensePayment paid = new HomeExpensePayment();
            paid.expenseId = home.id;
            paid.dueDate = today;
            paid.amount = new BigDecimal("1000.00");
            paid.source = "MANUAL";
            em.persist(paid);

            ReminderSent sent = new ReminderSent();
            sent.expenseId = home.id;
            sent.dueDate = today;
            sent.kind = "DUE";
            em.persist(sent);

            HePayment he = new HePayment();
            he.userId = userId;
            he.type = "PAY";
            he.amount = new BigDecimal("250.00");
            he.method = "PHONE";
            he.requestId = "req-" + tag;
            em.persist(he);

            em.persist(new Notification(userId, "INFO", "Note " + tag, null));

            Contact contact = new Contact();
            contact.userId = userId;
            contact.name = "Friend " + tag;
            contact.phoneKey = "9111111111";
            em.persist(contact);

            BankLink link = new BankLink();
            link.userId = userId;
            link.consentId = "consent-" + tag;
            link.rangeFrom = Instant.now().minusSeconds(86400);
            link.rangeTo = Instant.now();
            em.persist(link);

            BankAccount account = new BankAccount();
            account.userId = userId;
            account.consentId = "consent-" + tag;
            account.linkRefNumber = "ref-" + tag;
            em.persist(account);

            GuardianAlert alert = new GuardianAlert();
            alert.userId = userId;
            alert.alertKey = "alert-" + tag;
            em.persist(alert);

            GuardianBadge badge = new GuardianBadge();
            badge.userId = userId;
            badge.level = "BRONZE";
            badge.scoreWhenEarned = 40;
            em.persist(badge);

            GuardianPeriodBadge period = new GuardianPeriodBadge();
            period.userId = userId;
            period.kind = "WEEK";
            period.start = today;
            em.persist(period);

            GuardianScoreRecord score = new GuardianScoreRecord();
            score.userId = userId;
            score.day = today;
            score.score = 55;
            em.persist(score);

            GuardianScoreState state = new GuardianScoreState();
            state.userId = userId;
            em.persist(state);

            ScamReport report = new ScamReport();
            report.userId = userId;
            report.shieldLevel = "RED";
            report.userSays = "SAFE";
            em.persist(report);

            String marker = "{\"marker\":\"" + tag + "\"}";

            UserData data = em.find(UserData.class, userId);

            if (data == null) {
                em.persist(new UserData(userId, marker));
            } else {
                data.setJson(marker);
            }

            return home.id;
        });
    }

    private void addPayment(String id, Long sender, Long recipient, String status) {
        tx.executeWithoutResult(s -> {
            PaymentRecord p = new PaymentRecord();
            p.paymentId = id;
            p.userId = sender;
            p.recipientUserId = recipient;
            p.status = status;
            em.persist(p);
        });
    }

    private long count(String entity, Long userId) {
        return em.createQuery("select count(e) from " + entity + " e where e.userId = :id", Long.class)
                .setParameter("id", userId).getSingleResult();
    }

    /** How many rows this person has in each table (the two home-expense tables by their expense). */
    private Map<String, Long> counts(Long userId, long expenseId) {
        Map<String, Long> out = new TreeMap<>();

        for (String entity : OWNED_BY_USER) {
            out.put(entity, count(entity, userId));
        }

        for (String entity : OWNED_BY_EXPENSE) {
            out.put(entity, em.createQuery("select count(e) from " + entity + " e where e.expenseId = :id", Long.class)
                    .setParameter("id", expenseId).getSingleResult());
        }

        return out;
    }

    /** Rows in each table, whoever they belong to. */
    private Map<String, Long> totals() {
        Map<String, Long> out = new TreeMap<>();

        List<String> tables = new ArrayList<>(OWNED_BY_USER);
        tables.addAll(OWNED_BY_EXPENSE);
        tables.addAll(List.of("PaymentRecord", "UserAccount", "UserData"));

        for (String entity : tables) {
            out.put(entity, em.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult());
        }

        return out;
    }

    private String recordsOf(Long userId) {
        UserData data = em.find(UserData.class, userId);

        return data == null ? null : data.getJson();
    }

    private List<String> allPayments() {
        List<String> out = new ArrayList<>();

        for (PaymentRecord p : em.createQuery("select p from PaymentRecord p order by p.paymentId", PaymentRecord.class).getResultList()) {
            out.add(p.paymentId + "|" + p.userId + "|" + p.recipientUserId + "|" + p.status);
        }

        return out;
    }

    private Map<String, String> accounts() {
        Map<String, String> out = new TreeMap<>();

        for (UserAccount u : users.findAll()) {
            out.put(u.getEmail(), u.getId() + "|" + u.getPasswordHash() + "|" + u.getPhone());
        }

        return out;
    }
}
