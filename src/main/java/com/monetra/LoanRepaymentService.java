package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loan repayment awareness: which EMIs are coming, how much they add up to, and whether the
 * money a user has is projected to cover them after a payment. Plain arithmetic on saved data.
 */
@Service
public class LoanRepaymentService {

    private static final BigDecimal THIN_SHARE = new BigDecimal("0.10");
    private static final int MAX_INSTALLMENTS_LISTED = 60;

    /** One active loan from the user's records. */
    public record Loan(
            String id,
            String name,
            BigDecimal emi,
            LocalDate due,
            String frequency,
            BigDecimal outstanding,
            int installmentsLeft) {
    }

    /** One EMI that has to be paid on a date. */
    public record Installment(Loan loan, LocalDate date, boolean overdue) {
    }

    /** The user's active loans. A loan without a status is active. */
    public List<Loan> loans(JsonNode data) {
        List<Loan> out = new ArrayList<>();

        if (data == null) {
            return out;
        }

        for (JsonNode n : data.path("loans")) {
            if ("CLOSED".equals(n.path("status").asText(""))) {
                continue;
            }

            LocalDate due = null;

            try {
                due = LocalDate.parse(n.path("due").asText(""));
            } catch (Exception e) {
                /* a loan without a valid date still counts towards the monthly total */
            }

            String frequency = n.path("frequency").asText("MONTHLY");

            out.add(new Loan(
                    n.path("id").asText(""),
                    n.path("name").asText("Loan"),
                    BudgetRules.decimal(n.path("amount")),
                    due,
                    "WEEKLY".equals(frequency) || "YEARLY".equals(frequency) ? frequency : "MONTHLY",
                    BudgetRules.decimal(n.path("outstanding")),
                    Math.max(0, n.path("installmentsLeft").asInt(0))));
        }

        return out;
    }

    /** What all EMIs cost in an average month. */
    public BigDecimal monthlyEmi(List<Loan> loans) {
        BigDecimal total = BigDecimal.ZERO;

        for (Loan loan : loans) {
            if ("WEEKLY".equals(loan.frequency())) {
                total = total.add(loan.emi().multiply(BigDecimal.valueOf(52)).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));

            } else if ("YEARLY".equals(loan.frequency())) {
                total = total.add(loan.emi().divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP));

            } else {
                total = total.add(loan.emi());
            }
        }

        return BudgetRules.money(total);
    }

    public BigDecimal outstanding(List<Loan> loans) {
        BigDecimal total = BigDecimal.ZERO;

        for (Loan loan : loans) {
            total = total.add(loan.outstanding());
        }

        return BudgetRules.money(total);
    }

    /**
     * Every EMI that has to be paid up to a date, oldest first. An EMI whose date has passed is
     * still unpaid, so it is included once as overdue. Each EMI appears exactly once.
     */
    public List<Installment> schedule(List<Loan> loans, LocalDate today, LocalDate until) {
        List<Installment> out = new ArrayList<>();

        for (Loan loan : loans) {
            LocalDate date = loan.due();

            int count = 0;

            while (date != null && !date.isAfter(until)
                    && (loan.installmentsLeft() == 0 || count < loan.installmentsLeft())
                    && count < MAX_INSTALLMENTS_LISTED) {

                out.add(new Installment(loan, date, date.isBefore(today)));

                count++;

                date = "WEEKLY".equals(loan.frequency()) ? loan.due().plusWeeks(count)
                        : "YEARLY".equals(loan.frequency()) ? loan.due().plusYears(count)
                        : loan.due().plusMonths(count);
            }
        }

        out.sort(Comparator.comparing((Installment i) -> i.date()));

        return out;
    }

    public BigDecimal total(List<Installment> installments) {
        BigDecimal total = BigDecimal.ZERO;

        for (Installment i : installments) {
            total = total.add(i.loan().emi());
        }

        return BudgetRules.money(total);
    }

    /**
     * Is the money projected to cover the known commitments after a payment?
     *
     * projectedBalance = availableBalance + expectedIncomeBefore - proposedPayment
     *                    - EMIs - essential expenses - bills        (each counted once)
     *
     * HIGH: the projected balance is below zero. MEDIUM: it is positive but under the buffer the user
     * wants to keep (or under a tenth of what they have, when no buffer is set). LOW: otherwise.
     */
    public Map<String, Object> assess(
            BigDecimal availableBalance,
            BigDecimal expectedIncomeBefore,
            BigDecimal proposedPayment,
            BigDecimal emis,
            BigDecimal essentials,
            BigDecimal bills,
            BigDecimal buffer) {

        BigDecimal projected = availableBalance
                .add(expectedIncomeBefore)
                .subtract(proposedPayment)
                .subtract(emis)
                .subtract(essentials)
                .subtract(bills);

        BigDecimal cushion = buffer.signum() > 0 ? buffer : availableBalance.max(BigDecimal.ZERO).multiply(THIN_SHARE);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("availableBalance", BudgetRules.money(availableBalance));
        out.put("expectedIncomeBefore", BudgetRules.money(expectedIncomeBefore));
        out.put("proposedPayment", BudgetRules.money(proposedPayment));
        out.put("emis", BudgetRules.money(emis));
        out.put("essentials", BudgetRules.money(essentials));
        out.put("bills", BudgetRules.money(bills));
        out.put("projectedBalance", BudgetRules.money(projected));
        out.put("status", projected.signum() < 0 ? "HIGH" : projected.compareTo(cushion) < 0 ? "MEDIUM" : "LOW");
        return out;
    }

    /** Whole days from today to a date (negative when the date has passed). */
    public static long daysUntil(LocalDate today, LocalDate date) {
        return ChronoUnit.DAYS.between(today, date);
    }
}
