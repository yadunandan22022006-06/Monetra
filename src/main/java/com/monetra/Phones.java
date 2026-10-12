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
}
