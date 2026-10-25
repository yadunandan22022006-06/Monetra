package com.monetra;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the Try Demo account (demo1@monetra.test) back to clean sample data, for example before recording a video.
 * Runs only at start-up, and only when monetra.demo-reset=true (see DemoData). There is no web address for it.
 *
 * What keeps everyone else safe:
 *   - It finds the account by that exact email (unique, never changed, and nobody can sign up with it), never by a pattern.
 *   - Every delete is limited to that account's id, and the tables are listed by name below: no "delete all".
 *   - The PayPal payments table is never touched. Neither are the accounts themselves (sign-in, phone numbers, OTP).
 *   - It is one transaction: it all happens or none of it does.
 *   - The log gets counts only.
 *
 * Created only where the demo accounts are (monetra.seed-demo-users=true), so a real deployment cannot run it.
 */
@Component
@ConditionalOnProperty(name = "monetra.seed-demo-users", havingValue = "true")
public class DemoReset {

    private static final Logger log = LoggerFactory.getLogger(DemoReset.class);

    /** What a reset did: which tables lost how many of the demo account's rows, and how many PayPal payments were left alone. */
    public record Result(boolean done, Map<String, Integer> removed, int paymentsKept) {
    }

    /**
     * Table (entity) name -> the delete for it. Each one takes the demo account's id as :id and nothing else.
     * Not here on purpose: PaymentRecord (PayPal payments), UserAccount (the account), UserData (rewritten below).
     */
    static final Map<String, String> OWNED = new LinkedHashMap<>();

    static {
        /* Rows that hang off a home expense go first, while those expense rows still exist. */
        OWNED.put("HomeExpensePayment",
                "delete from HomeExpensePayment e where e.expenseId in (select h.id from HomeExpense h where h.userId = :id)");
        OWNED.put("ReminderSent",
                "delete from ReminderSent e where e.expenseId in (select h.id from HomeExpense h where h.userId = :id)");

        for (String entity : List.of(
                "HomeExpense", "HePayment", "Notification", "Contact", "BankAccount", "BankLink",
                "GuardianAlert", "GuardianBadge", "GuardianPeriodBadge", "GuardianScoreRecord", "GuardianScoreState",
                "ScamReport")) {

            OWNED.put(entity, "delete from " + entity + " e where e.userId = :id");
        }
    }

    private final UserAccountRepository users;
    private final UserDataRepository store;
    private final PaymentRepository payments;
    private final EntityManager em;

    public DemoReset(
            UserAccountRepository users,
            UserDataRepository store,
            PaymentRepository payments,
            EntityManager em) {

        this.users = users;
        this.store = store;
        this.payments = payments;
        this.em = em;
    }

    @Transactional
    public Result resetDemoAccount() {
        UserAccount demo = users.findByEmail(DemoData.DEMO1_EMAIL).orElse(null);

        if (demo == null) {
            log.info("Demo reset skipped: the demo account does not exist on this server.");

            return new Result(false, Map.of(), 0);
        }

        Long id = demo.getId();

        Map<String, Integer> removed = new LinkedHashMap<>();

        for (Map.Entry<String, String> delete : OWNED.entrySet()) {
            removed.put(delete.getKey(), em.createQuery(delete.getValue()).setParameter("id", id).executeUpdate());
        }

        /* The saved records (limits, expenses, loans, goals, savings) go back to the same sample the demo starts with. */
        String sample = DemoData.sampleRecords();

        store.findById(id).ifPresentOrElse(
                data -> data.setJson(sample),
                () -> store.save(new UserData(id, sample)));

        int kept = payments.findByUserIdOrderByCreatedAtDesc(id).size();

        log.info("Demo account reset to sample data. Rows removed: {}. PayPal payment records left untouched: {}.", removed, kept);

        return new Result(true, removed, kept);
    }
}
