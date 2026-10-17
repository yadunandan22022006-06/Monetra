package com.monetra;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a mobile number is written in a payment's name: the full number when it is known, never a guess. */
class PhonesTest {

    @Test
    void aNumberIsWrittenInTwoGroupsOfFive() {
        assertEquals("98765 43210", Phones.display("9876543210"));
        assertEquals("Contact 98765 43210", Phones.contactLabel("9876543210"));
        assertEquals("", Phones.display(null));
        assertEquals("", Phones.display("12345"));
    }

    @Test
    void anOlderMaskedLabelBecomesTheFullNumberWhenItIsTheMaskOfThatNumber() {
        assertEquals("Contact 98765 43210", Phones.fullLabel("Contact 98xxxxxx10", "9876543210"));
        assertEquals("You sent ₹222.00 to Contact 81234 56752.",
                Phones.fullLabel("You sent ₹222.00 to Contact 81xxxxxx52.", "8123456752"));

        /* Typed with a country code or spaces, it is still the same number. */
        assertEquals("Contact 98765 43210", Phones.fullLabel("Contact 98xxxxxx10", "+91 98765 43210"));
    }

    @Test
    void aMaskOfAnotherNumberIsNeverReplaced() {
        /*
         * A mask only keeps the first and last two digits, so it is replaced only by the number of the payment the text
         * belongs to (the caller always passes that payment's own number). A mask that does not fit that number stays.
         */
        assertEquals("Contact 81xxxxxx52", Phones.fullLabel("Contact 81xxxxxx52", "9876543210"));
        assertEquals("Contact 98xxxxxx11", Phones.fullLabel("Contact 98xxxxxx11", "9876543210"));
    }

    @Test
    void withoutAFullNumberTheTextStaysExactlyAsItWas() {
        assertEquals("Contact 98xxxxxx10", Phones.fullLabel("Contact 98xxxxxx10", null));
        assertEquals("Contact 98xxxxxx10", Phones.fullLabel("Contact 98xxxxxx10", ""));
        assertEquals("Contact 98xxxxxx10", Phones.fullLabel("Contact 98xxxxxx10", "98765"));
        assertEquals(null, Phones.fullLabel(null, "9876543210"));
    }

    @Test
    void namesThatAreNotMaskedLabelsAreLeftAlone() {
        assertEquals("Mom", Phones.fullLabel("Mom", "9876543210"));
        assertEquals("Ravi Demo", Phones.fullLabel("Ravi Demo", "9000000002"));
        assertEquals("Account ••••1234", Phones.fullLabel("Account ••••1234", null));
    }
}
