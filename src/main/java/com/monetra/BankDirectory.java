package com.monetra;

import java.util.Map;
import java.util.regex.Pattern;

/** Detects a bank from the first four letters of its IFSC code and validates bank-account input. */
public final class BankDirectory {

    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern ACCOUNT = Pattern.compile("^[0-9]{9,18}$");

    private static final Map<String, String> BANKS = Map.ofEntries(
            Map.entry("SBIN", "State Bank of India"),
            Map.entry("HDFC", "HDFC Bank"),
            Map.entry("ICIC", "ICICI Bank"),
            Map.entry("UTIB", "Axis Bank"),
            Map.entry("KKBK", "Kotak Mahindra Bank"),
            Map.entry("PUNB", "Punjab National Bank"),
            Map.entry("BARB", "Bank of Baroda"),
            Map.entry("CNRB", "Canara Bank"),
            Map.entry("UBIN", "Union Bank of India"),
            Map.entry("IDIB", "Indian Bank"),
            Map.entry("BKID", "Bank of India"),
            Map.entry("INDB", "IndusInd Bank"),
            Map.entry("YESB", "YES Bank"),
            Map.entry("IDFB", "IDFC FIRST Bank"),
            Map.entry("FDRL", "Federal Bank"),
            Map.entry("MAHB", "Bank of Maharashtra"),
            Map.entry("CBIN", "Central Bank of India"),
            Map.entry("IOBA", "Indian Overseas Bank"),
            Map.entry("PYTM", "Paytm Payments Bank"),
            Map.entry("AIRP", "Airtel Payments Bank"));

    private BankDirectory() {
    }

    public static boolean validIfsc(String ifsc) {
        return ifsc != null && IFSC.matcher(ifsc).matches();
    }

    public static boolean validAccount(String account) {
        return account != null && ACCOUNT.matcher(account).matches();
    }

    /** The bank name for a valid IFSC, or "Bank (" + prefix + ")" when the prefix is not in the list. */
    public static String bankName(String ifsc) {
        if (!validIfsc(ifsc)) {
            return "";
        }

        String prefix = ifsc.substring(0, 4);

        return BANKS.getOrDefault(prefix, "Bank (" + prefix + ")");
    }
}
