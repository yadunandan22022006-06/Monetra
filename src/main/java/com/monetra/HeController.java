package com.monetra;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HE: home expenses. A daily, monthly or yearly home expense is paid in one of five ways.
 *
 * Paid through PayPal (the configured sandbox): Scan and Pay, and Mobile number. These are
 * ordinary Monetra payments marked with their HE type, so they are created, approved on PayPal and
 * verified by the server exactly like any other payment.
 *
 * Recorded only, no money moved: Cash and Bank transfer. PayPal cannot send money to an Indian
 * bank account, so Monetra never starts a bank transfer; the user makes it in their bank's app
 * and records it here.
 *
 * My Payment QR is how the user gets paid: their own Monetra QR code.
 */
@RestController
@RequestMapping("/api/he")
public class HeController {

    private static final Set<String> TYPES = Set.of("DAILY", "MONTHLY", "YEARLY");
    private static final Set<String> DIGITAL = Set.of("SCAN", "MOBILE");
    private static final Set<String> RECORDED = Set.of("CASH", "BANK");
    private static final Set<String> TRANSFERS = Set.of("NEFT", "IMPS", "RTGS", "UPI");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000");

    private final HePaymentRepository recorded;
    private final PaymentRepository payments;
    private final PaypalClient paypal;

    public HeController(HePaymentRepository recorded, PaymentRepository payments, PaypalClient paypal) {
        this.recorded = recorded;
        this.payments = payments;
        this.paypal = paypal;
    }

    /** DAILY, MONTHLY or YEARLY, or null for anything else. */
    static String type(Object value) {
        String t = upper(value);

        return TYPES.contains(t) ? t : null;
    }

    /** SCAN or MOBILE. An HE payment through PayPal with no method named counts as MOBILE. */
    static String digitalMethod(Object value) {
        String m = upper(value);

        return DIGITAL.contains(m) ? m : "MOBILE";
    }

    /**
     * The payment methods, and what really happens for each with the payment provider this server
     * is set up with. The page shows these so nothing looks supported that is not.
     */
    @GetMapping("/methods")
    public ResponseEntity<?> methods(HttpSession session) {
        if (SessionUser.id(session) == null) {
            return unauthorized();
        }

        boolean ready = paypal.configured();

        String notReady = "PayPal is not set up on this server yet.";

        List<Map<String, Object>> out = new ArrayList<>();

        out.add(method("SCAN", "Scan & Pay", "PAYPAL", ready,
                ready ? "Scan a QR code. You approve on PayPal." : notReady));

        out.add(method("MYQR", "My Payment QR", "RECEIVE", true,
                "Your QR code, for others to pay you."));

        out.add(method("MOBILE", "Mobile Number", "PAYPAL", ready,
                ready ? "Pay a mobile number. You approve on PayPal." : notReady));

        out.add(method("BANK", "Bank Transfer", "RECORD", true,
                "Monetra does not start bank transfers. Pay in your bank's app, then record it here."));

        out.add(method("CASH", "Cash", "RECORD", true,
                "Record cash you paid. No money moves."));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", "PayPal Sandbox");
        body.put("providerReady", ready);
        body.put("methods", out);
        body.put("transferTypes", List.of("NEFT", "IMPS", "RTGS", "UPI"));
        return ResponseEntity.ok(body);
    }

    /** Every home expense payment of this user, newest first. */
    @GetMapping("/history")
    public ResponseEntity<?> history(HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        List<Map<String, Object>> out = new ArrayList<>();

        for (HePayment p : recorded.findByUserIdOrderByCreatedAtDesc(userId)) {
            out.add(row(p));
        }

        for (PaymentRecord p : payments.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (p.heType != null) {
                out.add(row(p));
            }
        }

        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("createdAt"))).reversed());

        return ResponseEntity.ok(out);
    }

    /**
     * Records a home expense the user paid in cash or by their own bank transfer. Nothing is sent
     * anywhere, and it is only recorded when the user confirms they really paid.
     */
    @PostMapping("/pay")
    public ResponseEntity<?> pay(@RequestBody Map<String, Object> body, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        String type = type(body.get("type"));

        if (type == null) {
            return bad("Choose a daily, monthly or yearly home expense.");
        }

        String method = upper(body.get("method"));

        if (!RECORDED.contains(method)) {
            return bad("Only cash and bank transfers are recorded here. Other methods are paid through PayPal.");
        }

        if (!Boolean.TRUE.equals(body.get("confirmed"))) {
            return bad("Confirm that you have made this payment before it is recorded as paid.");
        }

        BigDecimal amount;

        try {
            amount = new BigDecimal(String.valueOf(body.get("amountInr"))).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return bad("Enter a valid amount.");
        }

        if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            return bad("The amount must be between ₹0.01 and ₹1,00,00,000.");
        }

        /* The same press of the button is recorded once, however many times it arrives. */

        String requestId = RecipientResolver.text(body.get("requestId"), 40);

        if (!requestId.isEmpty()) {
            HePayment already = recorded.findByUserIdAndRequestId(userId, requestId).orElse(null);

            if (already != null) {
                return ResponseEntity.ok(row(already));
            }
        }

        HePayment p = new HePayment();
        p.userId = userId;
        p.type = type;
        p.amount = amount;
        p.description = RecipientResolver.text(body.get("description"), 100);
        p.method = method;
        p.recipient = RecipientResolver.text(body.get("recipient"), 60);
        p.requestId = requestId.isEmpty() ? null : requestId;

        if ("BANK".equals(method)) {
            String transfer = upper(body.get("transferType"));

            if (!TRANSFERS.contains(transfer)) {
                return bad("Choose how you sent it: NEFT, IMPS, RTGS or UPI.");
            }

            if (p.recipient.isEmpty()) {
                return bad("Enter who you paid.");
            }

            String ifsc = upper(body.get("ifsc"));
            String last4 = RecipientResolver.text(body.get("accountLast4"), 20);

            if (!ifsc.isEmpty() && !BankDirectory.validIfsc(ifsc)) {
                return bad("Enter a valid IFSC code, for example HDFC0001234, or leave it empty.");
            }

            if (!last4.isEmpty() && !last4.matches("[0-9]{4}")) {
                return bad("Enter only the last 4 digits of the account number, or leave it empty.");
            }

            /* Only the bank's name and the last four digits are kept. */

            p.detail = transfer
                    + (ifsc.isEmpty() ? "" : " • " + BankDirectory.bankName(ifsc))
                    + (last4.isEmpty() ? "" : " • ••••" + last4);

            p.reference = RecipientResolver.text(body.get("reference"), 40);
        }

        try {
            return ResponseEntity.ok(row(recorded.save(p)));

        } catch (RuntimeException duplicate) {
            /* Two presses arrived together: the first one was saved. */

            return recorded.findByUserIdAndRequestId(userId, requestId)
                    .<ResponseEntity<?>>map(saved -> ResponseEntity.ok(row(saved)))
                    .orElseGet(() -> bad("This payment could not be recorded. Try again."));
        }
    }

    private static Map<String, Object> method(String id, String label, String kind, boolean available, String how) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("kind", kind);
        m.put("available", available);
        m.put("how", how);
        return m;
    }

    private static Map<String, Object> row(HePayment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "he-" + p.id);
        m.put("transactionId", "HE-" + p.id);
        m.put("source", "RECORDED");
        m.put("type", p.type);
        m.put("amountInr", p.amount);
        m.put("description", p.description);
        m.put("method", p.method);
        m.put("recipient", p.recipient);
        m.put("detail", p.detail);
        m.put("reference", p.reference);
        m.put("status", "PAID");
        m.put("createdAt", p.createdAt.toString());
        return m;
    }

    private static Map<String, Object> row(PaymentRecord p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.paymentId);
        m.put("transactionId", p.captureId != null ? p.captureId : p.paymentId);
        m.put("source", "PAYPAL");
        m.put("type", p.heType);
        m.put("amountInr", p.amountInr);
        m.put("amountUsd", p.amountUsd);
        m.put("description", p.description);
        m.put("method", p.heMethod == null ? "MOBILE" : p.heMethod);
        m.put("recipient", p.recipientName);
        m.put("status", p.status);
        m.put("createdAt", p.createdAt);
        m.put("paymentId", p.paymentId);
        m.put("message", p.message);
        m.put("orderId", p.orderId);
        return m;
    }

    private static String upper(Object value) {
        return value == null ? "" : String.valueOf(value).trim().toUpperCase();
    }

    private static ResponseEntity<Map<String, String>> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in first."));
    }
}
