package com.monetra;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Money in the Guardian's messages is written the Indian way, the same as on screen: ₹1,00,010.00. */
class InrTest {

    @Test
    void smallAmountsHaveNoSeparatorUntilTheThousands() {
        assertEquals("₹0.00", Inr.of(BigDecimal.ZERO));
        assertEquals("₹5.00", Inr.of(new BigDecimal("5")));
        assertEquals("₹999.99", Inr.of(new BigDecimal("999.99")));
        assertEquals("₹1,000.00", Inr.of(new BigDecimal("1000")));
        assertEquals("₹99,999.00", Inr.of(new BigDecimal("99999")));
    }

    @Test
    void lakhsAndCroresAreGroupedInTwos() {
        assertEquals("₹1,00,000.00", Inr.of(new BigDecimal("100000")));
        assertEquals("₹1,00,010.00", Inr.of(new BigDecimal("100010")));
        assertEquals("₹1,50,000.50", Inr.of(new BigDecimal("150000.50")));
        assertEquals("₹12,34,567.89", Inr.of(new BigDecimal("1234567.89")));
        assertEquals("₹1,00,00,000.00", Inr.of(new BigDecimal("10000000")));
        assertEquals("₹99,99,99,999.00", Inr.of(new BigDecimal("999999999")));
        assertEquals("₹1,00,00,00,000.00", Inr.of(new BigDecimal("1000000000")));
    }

    @Test
    void negativeAndMissingAmountsAndRounding() {
        assertEquals("-₹1,000.00", Inr.of(new BigDecimal("-1000")));
        assertEquals("-₹99,95,856.89", Inr.of(new BigDecimal("-9995856.885")));
        assertEquals("₹0.00", Inr.of(null));
        assertEquals("₹2,325.97", Inr.of(new BigDecimal("2325.96875")));
        assertEquals("₹0.00", Inr.of(new BigDecimal("-0.001")));
    }

    @Test
    void aboutFiguresAreWholeRupeesWithTheSameGrouping() {
        assertEquals("₹1,50,000", Inr.whole(new BigDecimal("149999.50")));
        assertEquals("₹500", Inr.whole(new BigDecimal("500.49")));
        assertEquals("₹0", Inr.whole(null));
    }

    @Test
    void datesAreWrittenDayMonthYear() {
        assertEquals("5 Nov 2026", Inr.date(LocalDate.of(2026, 11, 5)));
        assertEquals("", Inr.date(null));
    }
}
