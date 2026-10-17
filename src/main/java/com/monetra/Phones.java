package com.monetra;

/** Phone-number helpers shared by registration, lookup and QR codes. */
public final class Phones {

    private Phones() {
    }

    /** The last 10 digits of the number, or null if it has fewer than 10 digits. */
    public static String key(String phone) {
        if (phone == null) {
            return null;
        }

        String digits = phone.replaceAll("\\D", "");

        return digits.length() < 10 ? null : digits.substring(digits.length() - 10);
    }

    /** "98xxxxxx10" style mask for showing a number without exposing all of it. */
    public static String mask(String key) {
        if (key == null || key.length() < 10) {
            return "";
        }

        return key.substring(0, 2) + "xxxxxx" + key.substring(8);
    }

    /** "98765 43210": the full number, written the way the app shows a saved contact's. */
    public static String display(String key) {
        if (key == null || key.length() < 10) {
            return "";
        }

        return key.substring(0, 5) + " " + key.substring(5);
    }

    /** What a payment is called when the sender has no name for the number: "Contact 98765 43210". */
    public static String contactLabel(String key) {
        return "Contact " + display(key);
    }

    /**
     * Older payments were labelled with the masked number ("Contact 98xxxxxx10"). When the payment itself has the
     * full number, the text says that instead. The mask is only replaced when it is the mask of THAT number, and when
     * there is no full number the text is left exactly as it was: digits are never guessed.
     */
    public static String fullLabel(String text, String phone) {
        String key = key(phone);

        if (text == null || key == null) {
            return text;
        }

        String masked = "Contact " + mask(key);

        return text.contains(masked) ? text.replace(masked, contactLabel(key)) : text;
    }
}
