package com.monetra;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Works out who a payment is going to. The sender never types a name: the server finds it
 * from a mobile number or QR code, or labels a bank account by its last 4 digits.
 */
@Component
public class RecipientResolver {

    public static final BigDecimal MAX_INR = new BigDecimal("100000");

    /** The person or account a payment is going to. */
    public record Recipient(
            String name,
            String key,
            Long userId,
            String phone,
            String bankName,
            String ifsc,
            String last4) {
    }

    private final UserAccountRepository users;

    public RecipientResolver(UserAccountRepository users) {
        this.users = users;
    }

    /** Throws IllegalArgumentException with a friendly message if the details are not valid. */
    public Recipient resolve(Map<String, Object> body, Long senderId) {
        String method = method(body.get("method"));

        if ("BANK".equals(method)) {
            String ifsc = text(body.get("ifsc"), 11).toUpperCase();
            String account = text(body.get("accountNumber"), 18).replaceAll("\\s", "");

            if (!BankDirectory.validIfsc(ifsc)) {
                throw new IllegalArgumentException("Enter a valid IFSC code, for example HDFC0001234.");
            }

            if (!BankDirectory.validAccount(account)) {
                throw new IllegalArgumentException("Enter a valid bank account number (9 to 18 digits).");
            }

            String last4 = account.substring(account.length() - 4);
            String typed = text(body.get("recipientName"), 60);
            String bank = BankDirectory.bankName(ifsc);

            return new Recipient(
                    typed.isEmpty() ? "Account ••••" + last4 : typed,
                    "B:" + ifsc + ":" + last4,
                    null, null, bank, ifsc, last4);
        }

        String key = Phones.key(text(body.get("recipientPhone"), 20));

        if (key == null) {
            throw new IllegalArgumentException("Enter a valid 10-digit mobile number or scan a QR code.");
        }

        UserAccount found = users.findFirstByPhoneKey(key).orElse(null);

        if (found != null && found.getId().equals(senderId)) {
            throw new IllegalArgumentException("You cannot pay yourself.");
        }

        /* A name read from a scanned QR code is only a label for someone not on Monetra. */
        String scanned = text(body.get("scannedName"), 60);

        String label = found != null
                ? found.displayName()
                : (scanned.isEmpty() ? "Contact " + Phones.mask(key) : scanned);

        return new Recipient(
                label,
                "P:" + key,
                found == null ? null : found.getId(),
                key, null, null, null);
    }

    public static BigDecimal parseAmount(Object value) {
        BigDecimal amount;

        try {
            amount = new BigDecimal(text(value, 20)).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid amount.");
        }

        if (amount.signum() <= 0 || amount.compareTo(MAX_INR) > 0) {
            throw new IllegalArgumentException(
                    "Amount must be between 0.01 and " + MAX_INR.toPlainString() + " INR.");
        }

        return amount;
    }

    public static String method(Object value) {
        String m = text(value, 8).toUpperCase();

        return "BANK".equals(m) || "QR".equals(m) ? m : "PHONE";
    }

    public static String text(Object value, int max) {
        if (value == null) {
            return "";
        }

        String s = String.valueOf(value).trim();

        return s.length() > max ? s.substring(0, max) : s;
    }
}
