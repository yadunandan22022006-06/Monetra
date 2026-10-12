package com.monetra;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure scheduling rules for household expenses. No database, no clock. */
public final class HomeExpenseRules {

    public static final Set<String> FREQUENCIES = Set.of("ONCE", "DAILY", "WEEKLY", "MONTHLY", "YEARLY");

    public static final List<String> CATEGORIES = List.of(
            "Family", "Rent", "Electricity", "Water", "Internet", "Groceries",
            "College", "Vehicle", "Medical", "Insurance", "Other");

    private static final int MAX_OCCURRENCES = 400;

    private HomeExpenseRules() {
    }

    /** Every due date of an expense between from and to (both included). */
    public static List<LocalDate> occurrences(String frequency, LocalDate start, LocalDate from, LocalDate to) {
        List<LocalDate> out = new ArrayList<>();

        if (start == null || from == null || to == null || to.isBefore(from)) {
            return out;
        }

        switch (frequency) {
            case "ONCE" -> {
                if (!start.isBefore(from) && !start.isAfter(to)) {
                    out.add(start);
                }
            }

            case "DAILY" -> {
                LocalDate d = start.isAfter(from) ? start : from;

                while (!d.isAfter(to) && out.size() < MAX_OCCURRENCES) {
                    out.add(d);
                    d = d.plusDays(1);
                }
            }

            case "WEEKLY" -> {
                LocalDate d = start;

                if (d.isBefore(from)) {
                    long weeks = (java.time.temporal.ChronoUnit.DAYS.between(d, from) + 6) / 7;
                    d = d.plusWeeks(weeks);
                }

                while (!d.isAfter(to) && out.size() < MAX_OCCURRENCES) {
                    out.add(d);
                    d = d.plusWeeks(1);
                }
            }

            case "MONTHLY" -> {
                YearMonth ym = YearMonth.from(start);
                YearMonth first = YearMonth.from(from);

                if (first.isAfter(ym)) {
                    ym = first;
                }

                while (out.size() < MAX_OCCURRENCES) {
                    LocalDate d = ym.atDay(Math.min(start.getDayOfMonth(), ym.lengthOfMonth()));

                    if (d.isAfter(to)) {
                        break;
                    }

                    if (!d.isBefore(from) && !d.isBefore(start)) {
                        out.add(d);
                    }

                    ym = ym.plusMonths(1);
                }
            }

            case "YEARLY" -> {
                int year = Math.max(start.getYear(), from.getYear());

                while (out.size() < MAX_OCCURRENCES) {
                    YearMonth ym = YearMonth.of(year, start.getMonth());
                    LocalDate d = ym.atDay(Math.min(start.getDayOfMonth(), ym.lengthOfMonth()));

                    if (d.isAfter(to)) {
                        break;
                    }

                    if (!d.isBefore(from) && !d.isBefore(start)) {
                        out.add(d);
                    }

                    year++;
                }
            }

            default -> {
            }
        }

        return out;
    }

    /** PAID, DUE_TODAY, OVERDUE or UPCOMING. */
    public static String status(LocalDate due, boolean paid, LocalDate today) {
        if (paid) {
            return "PAID";
        }

        if (due.isBefore(today)) {
            return "OVERDUE";
        }

        return due.isEqual(today) ? "DUE_TODAY" : "UPCOMING";
    }
}
