package com.monetra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A key pasted into a hosting dashboard with a stray space, line break or quote marks still works. */
class PaypalSettingsTest {

    @Test
    void spacesLineBreaksAndQuotesAroundAValueAreIgnored() {
        assertEquals("abc123", PaypalClient.clean("abc123"));
        assertEquals("abc123", PaypalClient.clean("  abc123 "));
        assertEquals("abc123", PaypalClient.clean("abc123\r\n"));
        assertEquals("abc123", PaypalClient.clean("\"abc123\""));
        assertEquals("abc123", PaypalClient.clean("'abc123'"));
        assertEquals("abc123", PaypalClient.clean(" \"abc123\" \n"));
    }

    @Test
    void anEmptyOrMissingValueStaysEmptyAndInnerCharactersAreKept() {
        assertEquals("", PaypalClient.clean(null));
        assertEquals("", PaypalClient.clean(""));
        assertEquals("", PaypalClient.clean("   "));
        assertEquals("", PaypalClient.clean("\"\""));
        assertEquals("a\"b", PaypalClient.clean("a\"b"));
        assertEquals("ab-cd_ef", PaypalClient.clean("ab-cd_ef"));
    }
}
