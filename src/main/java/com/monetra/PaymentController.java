package com.monetra;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PayPal Sandbox payments for Monetra.
 *
 * Rules this class follows:
 *  - PayPal credentials stay on the server (environment variables).
 *  - The server decides the amount that is charged; the browser only suggests it.
 *  - The recipient's name is found by the server from the phone number or QR code,
 *    never typed in by the sender.
 *  - A payment is COMPLETED only after PayPal confirms the capture and the
 *    captured amount matches what the server created.
 *  - Capturing twice returns the stored result and never charges again.
 *  - Payments are saved in the database. The sender is the logged-in user
 *    (from the session, never from the request body). A receiver on Monetra sees
 *    the payment in their history and gets a notification once it is confirmed.
 *  - Full bank account numbers are never stored, only the IFSC and last 4 digits.
 *
 * PROTOTYPE LIMIT: the per-payment lock is in-process, so run a single server
 * instance (or switch to database row locks before scaling out).
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    @Value("${monetra.inr-per-usd:88}")
    private BigDecimal inrPerUsd;

    /** A second click on the same payment within this time gets the first order back instead of a new one. */
    private static final long DOUBLE_CLICK_SECONDS = 8;

    /** The PayPal approval address of each order made a moment ago, for the repeated click above. */
    private final Map<String, String> recentApprovals = new ConcurrentHashMap<>();

    private final PaypalClient paypal;
    private final PaymentService service;
    private final PaymentRepository payments;
    private final UserAccountRepository users;
    private final RecipientResolver recipients;
    private final HomeExpenseService home;

    public PaymentController(
            PaypalClient paypal,
            PaymentService service,
            PaymentRepository payments,
            UserAccountRepository users,
            RecipientResolver recipients,
            HomeExpenseService home) {

        this.paypal = paypal;
        this.service = service;
        this.payments = payments;
        this.users = users;
        this.recipients = recipients;
        this.home = home;
    }

    /* ------------------------------------------------------------------ */
    /* Lookups used by the Pay screen                                      */
    /* ------------------------------------------------------------------ */

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> out = new HashMap<>();
        out.put("inrPerUsd", inrPerUsd);
        out.put("currency", "USD");
        out.put("paypalConfigured", paypal.configured());
        out.put("webhookConfigured", paypal.webhookConfigured());
        return out;
    }

    /** Finds who owns a mobile number, so the sender sees a name instead of typing one. */
    @GetMapping("/recipient")
    public ResponseEntity<?> recipient(@RequestParam String phone, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in first.");
        }

        String key = Phones.key(phone);

        if (key == null) {
            return error(HttpStatus.BAD_REQUEST, "Enter a valid 10-digit mobile number.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phone", key);
        out.put("maskedPhone", Phones.mask(key));

        UserAccount found = users.findFirstByPhoneKey(key).orElse(null);

        if (found == null) {
            out.put("onMonetra", false);
            out.put("name", recipients.labelFor(userId, key));
        } else if (found.getId().equals(userId)) {
            out.put("onMonetra", true);
            out.put("self", true);
            out.put("name", found.displayName());
        } else {
            out.put("onMonetra", true);
            out.put("name", found.displayName());
        }

        return ResponseEntity.ok(out);
    }

    /** Detects the bank from an IFSC code. */
    @GetMapping("/bank")
    public ResponseEntity<?> bank(@RequestParam String ifsc, HttpSession session) {
        if (SessionUser.id(session) == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in first.");
        }

        String code = ifsc.trim().toUpperCase();

        if (!BankDirectory.validIfsc(code)) {
            return error(HttpStatus.BAD_REQUEST, "That IFSC code does not look right.");
        }

        return ResponseEntity.ok(Map.of("ifsc", code, "bankName", BankDirectory.bankName(code)));
    }

    /** What the Fraud Guard says about a payment, before any order is created. */
    @PostMapping("/risk")
    public ResponseEntity<?> risk(@RequestBody Map<String, Object> body, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in first.");
        }

        RecipientResolver.Recipient to;
        BigDecimal amount;

        try {
            to = recipients.resolve(body, userId);
            amount = RecipientResolver.parseAmount(body.get("amountInr"));

        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        List<String> flags = flags(userId, to, amount);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flagged", !flags.isEmpty());
        out.put("flags", flags);
        out.put("recipientName", to.name());
        return ResponseEntity.ok(out);
    }

    /* ------------------------------------------------------------------ */
    /* Create, capture, cancel, refresh                                    */
    /* ------------------------------------------------------------------ */

    @PostMapping("/create-order")
    public ResponseEntity<?> createOrder(
            @RequestBody Map<String, Object> body,
            HttpSession session) {

        Long userId = SessionUser.id(session);

        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in first.");
        }

        if (!paypal.configured()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "PayPal credentials are not configured on the server.");
        }

        RecipientResolver.Recipient to;
        BigDecimal amountInr;

        try {
            to = recipients.resolve(body, userId);
            amountInr = RecipientResolver.parseAmount(body.get("amountInr"));

        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        String description = RecipientResolver.text(body.get("description"), 100);
        /* A method that is not one of ours is refused, not quietly treated as a phone payment. */
        String asked = RecipientResolver.text(body.get("method"), 12).toUpperCase();

        if (!asked.isEmpty() && !List.of("PHONE", "QR", "BANK").contains(asked)) {
            return error(HttpStatus.BAD_REQUEST, "That payment method is not supported.");
        }

        String method = RecipientResolver.method(body.get("method"));

        /* Does this payment settle a household expense? */

        Long homeExpenseId = null;
        String homeDueDate = null;

        if (body.get("homeExpenseId") != null) {
            try {
                homeExpenseId = Long.valueOf(String.valueOf(body.get("homeExpenseId")));
                homeDueDate = LocalDate.parse(String.valueOf(body.get("homeDueDate"))).toString();

            } catch (RuntimeException e) {
                return error(HttpStatus.BAD_REQUEST, "That household expense is not valid.");
            }

            final Long expenseId = homeExpenseId;

            if (home.list(userId).stream().noneMatch(x -> x.id.equals(expenseId))) {
                return error(HttpStatus.BAD_REQUEST, "That household expense is not valid.");
            }
        }

        /*
         * The button pressed twice (or a request sent twice): the same order comes back and nothing new is created.
         * This comes before the Fraud Guard, which would otherwise count the first order against the second.
         */

        PaymentRecord same = recentSameOrder(userId, to.key(), amountInr, method, homeExpenseId, homeDueDate,
                HeController.type(body.get("heType")));

        if (same != null) {
            return ResponseEntity.ok(orderReply(same, recentApprovals.get(same.paymentId)));
        }

        List<String> flags = flags(userId, to, amountInr);

        if (!flags.isEmpty() && !Boolean.TRUE.equals(body.get("confirmRisk"))) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", "The Fraud Guard needs you to confirm this payment.");
            out.put("needsConfirmation", true);
            out.put("flags", flags);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(out);
        }

        BigDecimal amountUsd = amountInr.divide(inrPerUsd, 2, RoundingMode.HALF_UP);

        if (amountUsd.signum() <= 0) {
            return error(HttpStatus.BAD_REQUEST, "Amount is too small to charge in USD.");
        }

        PaymentRecord rec = new PaymentRecord();
        rec.paymentId = UUID.randomUUID().toString();
        rec.userId = userId;
        rec.recipientUserId = to.userId();
        rec.recipientKey = to.key();
        rec.method = method;
        rec.recipientName = to.name();
        rec.recipientPhone = to.phone();
        rec.bankName = to.bankName();
        rec.ifsc = to.ifsc();
        rec.bankLast4 = to.last4();
        rec.description = description;
        rec.riskFlags = flags.isEmpty() ? null : String.join(" | ", flags);
        rec.homeExpenseId = homeExpenseId;
        rec.homeDueDate = homeDueDate;
        rec.heType = HeController.type(body.get("heType"));
        rec.heMethod = rec.heType == null ? null : HeController.digitalMethod(body.get("heMethod"));
        rec.amountInr = amountInr;
        rec.amountUsd = amountUsd;
        rec.status = "CREATED";
        rec.createdAt = Instant.now().toString();

        payments.save(rec);

        try {
            String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();

            Map<String, Object> amount = new HashMap<>();
            amount.put("currency_code", "USD");
            amount.put("value", amountUsd.toPlainString());

            Map<String, Object> unit = new HashMap<>();
            unit.put("reference_id", rec.paymentId);
            unit.put("description", description.isEmpty() ? "Monetra payment" : "Monetra: " + description);
            unit.put("amount", amount);

            Map<String, Object> context = new HashMap<>();
            context.put("brand_name", "Monetra");
            context.put("user_action", "PAY_NOW");
            context.put("shipping_preference", "NO_SHIPPING");

            context.put("return_url", base + "/?paypal=return&ref=" + rec.paymentId);
            context.put("cancel_url", base + "/?paypal=cancel&ref=" + rec.paymentId);

            Map<String, Object> order = new HashMap<>();
            order.put("intent", "CAPTURE");
            order.put("purchase_units", Collections.singletonList(unit));
            order.put("application_context", context);

            Map<String, Object> json = paypal.post("/v2/checkout/orders", order, rec.paymentId);

            String orderId = PaypalClient.text(json.get("id"));
            String approveUrl = "";

            for (Object item : PaypalClient.list(json.get("links"))) {
                Map<String, Object> link = PaypalClient.map(item);
                String rel = PaypalClient.text(link.get("rel"));

                if ("approve".equals(rel) || "payer-action".equals(rel)) {
                    approveUrl = PaypalClient.text(link.get("href"));
                    break;
                }
            }

            if (orderId.isEmpty() || approveUrl.isEmpty()) {
                throw new IllegalStateException("PayPal response had no approval link.");
            }

            rec.orderId = orderId;
            rec.status = "AWAITING_APPROVAL";
            payments.save(rec);

            if (recentApprovals.size() > 200) {
                recentApprovals.clear();
            }

            recentApprovals.put(rec.paymentId, approveUrl);

            return ResponseEntity.ok(orderReply(rec, approveUrl));

        } catch (HttpStatusCodeException e) {
            log.error("PayPal rejected create-order: {} {}", e.getStatusCode(), e.getResponseBodyAsString());

            String why = PaypalClient.errorSummary(e);

            rec.status = "FAILED";
            rec.message = limit("PayPal rejected the order (" + why + ").");
            payments.save(rec);
            return error(HttpStatus.BAD_GATEWAY, "PayPal rejected the order: " + why);

        } catch (RestClientException | IllegalStateException e) {
            log.error("Could not create PayPal order", e);
            rec.status = "FAILED";
            rec.message = "Could not reach PayPal.";
            payments.save(rec);
            return error(HttpStatus.BAD_GATEWAY, "Could not reach PayPal. Try again.");
        }
    }

    /** What the browser needs to send the user to PayPal. */
    private static Map<String, Object> orderReply(PaymentRecord rec, String approvalUrl) {
        Map<String, Object> out = new HashMap<>();
        out.put("paymentId", rec.paymentId);
        out.put("orderId", rec.orderId);
        out.put("approvalUrl", approvalUrl);
        out.put("amountUsd", rec.amountUsd);
        out.put("recipientName", rec.recipientName);
        return out;
    }

    /**
     * The order this user made a moment ago for exactly the same payment (same receiver, amount, method and bill)
     * that is still waiting for approval and whose approval address is known, or null.
     */
    private PaymentRecord recentSameOrder(Long userId, String key, BigDecimal amountInr, String method,
            Long expenseId, String dueDate, String heType) {

        Instant limit = Instant.now().minusSeconds(DOUBLE_CLICK_SECONDS);

        for (PaymentRecord rec : payments.findByUserIdOrderByCreatedAtDesc(userId)) {
            Instant at;

            try {
                at = Instant.parse(rec.createdAt);

            } catch (RuntimeException e) {
                continue;
            }

            if (at.isBefore(limit)) {
                return null;   /* newest first: nothing older can be a double click */
            }

            if ("AWAITING_APPROVAL".equals(rec.status)
                    && recentApprovals.containsKey(rec.paymentId)
                    && Objects.equals(key, rec.recipientKey)
                    && rec.amountInr != null && rec.amountInr.compareTo(amountInr) == 0
                    && Objects.equals(method, rec.method)
                    && Objects.equals(expenseId, rec.homeExpenseId)
                    && Objects.equals(dueDate, rec.homeDueDate)
                    && Objects.equals(heType, rec.heType)) {

                return rec;
            }
        }

        return null;
    }

    @PostMapping("/capture/{orderId}")
    public ResponseEntity<?> capture(
            @PathVariable String orderId,
            HttpSession session) {

        Long userId = SessionUser.id(session);

        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in first.");
        }

        PaymentRecord found = payments.findByOrderId(orderId).orElse(null);

        if (found == null) {
            return error(HttpStatus.NOT_FOUND, "Unknown payment order.");
        }

        if (!userId.equals(found.userId)) {
            return error(HttpStatus.FORBIDDEN, "This payment belongs to another account.");
        }

        synchronized (service.lock(found.paymentId)) {
            return captureLocked(found.paymentId, userId);
        }
    }

    /**
     * Asks PayPal where a payment really stands and fixes our record to match.
     * This is the recovery path when a return, a capture, a payout or a sync went wrong.
     */
    @PostMapping("/{paymentId}/refresh")
    public ResponseEntity<?> refresh(@PathVariable String paymentId, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in first.");
        }

        PaymentRecord found = payments.findById(paymentId).orElse(null);

        if (found == null) {
            return error(HttpStatus.NOT_FOUND, "Unknown payment.");
        }

        if (!userId.equals(found.userId)) {
            return error(HttpStatus.FORBIDDEN, "This payment belongs to another account.");
        }

        synchronized (service.lock(paymentId)) {
            PaymentRecord rec = payments.findById(paymentId).orElse(found);

            if ("COMPLETED".equals(rec.status)) {
                service.payoutIfPossible(rec);   /* retries a payout that is waiting or failed */
                return ResponseEntity.ok(view(rec, userId));
            }

            if (rec.orderId == null) {
                return ResponseEntity.ok(view(rec, userId));
            }

            try {
                Map<String, Object> order = paypal.get("/v2/checkout/orders/" + rec.orderId);

                String orderStatus = PaypalClient.text(order.get("status"));

                if ("APPROVED".equals(orderStatus)) {
                    /* Only a payment still open is captured; a failed, cancelled or pending one reports what it is. */
                    if (!"AWAITING_APPROVAL".equals(rec.status)) {
                        return ResponseEntity.ok(view(rec, userId));
                    }

                    return captureLocked(paymentId, userId);
                }

                if ("COMPLETED".equals(orderStatus)) {
                    applyCapture(rec, order);
                    payments.save(rec);
                    return ResponseEntity.ok(view(rec, userId));
                }

                if ("VOIDED".equals(orderStatus)) {
                    rec.status = "FAILED";
                    rec.message = "PayPal cancelled this order. No money was taken. Start a new payment.";
                } else {
                    rec.message = "Still waiting for you to approve this payment on PayPal. "
                            + "If you closed the PayPal window, start a new payment.";
                }

                payments.save(rec);

                return ResponseEntity.ok(view(rec, userId));

            } catch (HttpStatusCodeException e) {
                log.error("PayPal refresh failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                return error(HttpStatus.BAD_GATEWAY, recovery(e.getStatusCode().value()));

            } catch (RestClientException | IllegalStateException e) {
                log.error("Could not refresh PayPal order", e);
                return error(HttpStatus.BAD_GATEWAY,
                        "Monetra could not reach PayPal just now. Nothing was lost. Try Refresh again in a minute.");
            }
        }
    }

    @PostMapping("/{paymentId}/cancel")
    public ResponseEntity<?> cancel(
            @PathVariable String paymentId,
            HttpSession session) {

        Long userId = SessionUser.id(session);

        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in first.");
        }

        PaymentRecord found = payments.findById(paymentId).orElse(null);

        if (found == null) {
            return error(HttpStatus.NOT_FOUND, "Unknown payment.");
        }

        if (!userId.equals(found.userId)) {
            return error(HttpStatus.FORBIDDEN, "This payment belongs to another account.");
        }

        synchronized (service.lock(paymentId)) {
            PaymentRecord rec = payments.findById(paymentId).orElse(found);

            if ("CREATED".equals(rec.status) || "AWAITING_APPROVAL".equals(rec.status)) {
                rec.status = "CANCELLED";
                rec.message = "Cancelled by the user.";
                payments.save(rec);
            }

            return ResponseEntity.ok(view(rec, userId));
        }
    }

    /** Sent payments (any status) and confirmed received payments, newest first. */
    @GetMapping
    public ResponseEntity<?> list(HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return error(HttpStatus.UNAUTHORIZED, "Please log in first.");
        }

        List<PaymentRecord> all = new ArrayList<>(payments.findByUserIdOrderByCreatedAtDesc(userId));

        all.addAll(payments.findByRecipientUserIdAndStatusOrderByCreatedAtDesc(userId, "COMPLETED"));

        all.sort(Comparator.comparing((PaymentRecord r) -> r.createdAt).reversed());

        List<Map<String, Object>> out = new ArrayList<>();

        for (PaymentRecord rec : all) {
            /* Home expense (HE) payments the user sent have their own history under HE. */
            if (rec.heType != null && userId.equals(rec.userId)) {
                continue;
            }

            out.add(view(rec, userId));
        }

        return ResponseEntity.ok(out);
    }

    /* ------------------------------------------------------------------ */
    /* Capture                                                             */
    /* ------------------------------------------------------------------ */

    /** Must be called while holding the payment's lock. */
    private ResponseEntity<?> captureLocked(String paymentId, Long userId) {

        PaymentRecord rec = payments.findById(paymentId).orElse(null);

        if (rec == null) {
            return error(HttpStatus.NOT_FOUND, "Unknown payment order.");
        }

        /* Already captured: return the stored result, never capture twice. */
        if ("COMPLETED".equals(rec.status)) {
            return ResponseEntity.ok(view(rec, userId));
        }

        if (!"AWAITING_APPROVAL".equals(rec.status)) {
            return error(HttpStatus.CONFLICT,
                    "This payment can no longer be captured (status: " + rec.status + ").");
        }

        try {
            Map<String, Object> json = paypal.post(
                    "/v2/checkout/orders/" + rec.orderId + "/capture", "{}", rec.paymentId + "-capture");

            applyCapture(rec, json);

            payments.save(rec);

            return ResponseEntity.ok(view(rec, userId));

        } catch (HttpStatusCodeException e) {
            String bodyText = e.getResponseBodyAsString();

            if (e.getStatusCode().value() == 422 && bodyText.contains("ORDER_NOT_APPROVED")) {
                return error(HttpStatus.CONFLICT,
                        "The payment has not been approved on PayPal yet.");
            }

            log.error("PayPal capture failed: {} {}", e.getStatusCode(), bodyText);

            if (e.getStatusCode().value() == 429 || e.getStatusCode().value() >= 500) {
                /*
                 * PayPal is busy, not refusing: as far as Monetra knows the payment is neither captured nor failed.
                 * It stays open, so the same capture can be asked again (PayPal answers a repeat with the first
                 * result) or the order can be checked with Refresh.
                 */
                return error(HttpStatus.BAD_GATEWAY, recovery(e.getStatusCode().value()));
            }

            rec.status = "FAILED";
            rec.message = limit(recovery(e.getStatusCode().value()) + " (PayPal said: " + PaypalClient.errorSummary(e) + ")");
            payments.save(rec);

            service.notifyUser(rec.userId, "PAYMENT_FAILED",
                    "Your payment of " + PaymentService.rupees(rec.amountInr) + " to "
                            + Phones.fullLabel(rec.recipientName, rec.recipientPhone)
                            + " did not go through. No money was taken.", rec.paymentId);

            return error(HttpStatus.BAD_GATEWAY, rec.message);

        } catch (RestClientException | IllegalStateException e) {
            log.error("Could not capture PayPal order", e);

            return error(HttpStatus.BAD_GATEWAY,
                    "Could not reach PayPal. Your payment is safe. Use Refresh on the Transactions page to check it again.");
        }
    }

    /**
     * Reads a PayPal order or capture reply and sets the record's status.
     * COMPLETED only if PayPal says so AND the captured amount matches ours.
     */
    private void applyCapture(PaymentRecord rec, Map<String, Object> json) {
        String orderStatus = PaypalClient.text(json.get("status"));

        Map<String, Object> unit = PaypalClient.map(PaypalClient.first(PaypalClient.list(json.get("purchase_units"))));
        Map<String, Object> paypalPayments = PaypalClient.map(unit.get("payments"));
        Map<String, Object> capture = PaypalClient.map(PaypalClient.first(PaypalClient.list(paypalPayments.get("captures"))));
        Map<String, Object> captured = PaypalClient.map(capture.get("amount"));

        String captureStatus = PaypalClient.text(capture.get("status"));
        String currency = PaypalClient.text(captured.get("currency_code"));
        String value = PaypalClient.text(captured.get("value"));

        boolean amountMatches;

        try {
            amountMatches = new BigDecimal(value).compareTo(rec.amountUsd) == 0;
        } catch (NumberFormatException e) {
            amountMatches = false;
        }

        if ("COMPLETED".equals(orderStatus)
                && "COMPLETED".equals(captureStatus)
                && "USD".equals(currency)
                && amountMatches) {

            if (!"COMPLETED".equals(rec.status)) {
                service.completed(rec, PaypalClient.text(capture.get("id")));
            }

        } else if ("COMPLETED".equals(orderStatus) && "PENDING".equals(captureStatus)
                && "USD".equals(currency) && amountMatches) {

            /* PayPal is holding the money for review: not paid yet, not failed either. Refresh or PayPal's webhook settles it. */
            rec.status = "PENDING";
            rec.message = "PayPal is still reviewing this payment, so it is not confirmed yet. Nothing is shown as paid until PayPal confirms it.";

        } else {
            log.warn("Capture not verified: order={} capture={} {} {}",
                    orderStatus, captureStatus, currency, value);

            rec.status = "FAILED";
            rec.message = "PayPal did not confirm this payment (status " + captureStatus + ").";
        }
    }

    /** Explains a PayPal HTTP error in plain language. */
    private static String recovery(int status) {
        if (status == 401 || status == 403) {
            return "PayPal did not accept Monetra's sign-in. The server's PayPal credentials need to be checked. "
                    + "No money was taken.";
        }

        if (status == 422) {
            return "PayPal could not complete this payment (for example the buyer's balance or card was declined). "
                    + "No money was taken. Try a different PayPal account or a smaller amount.";
        }

        if (status == 429 || status >= 500) {
            return "PayPal is busy or having problems. Nothing was lost. Wait a minute and press Refresh to check again.";
        }

        return "PayPal could not complete this payment. No money was taken. Check the server log for details.";
    }

    /* ------------------------------------------------------------------ */
    /* Fraud flags and views                                               */
    /* ------------------------------------------------------------------ */

    private List<String> flags(Long userId, RecipientResolver.Recipient to, BigDecimal amount) {
        List<FraudRules.Past> past = new ArrayList<>();

        for (PaymentRecord rec : payments.findByUserIdOrderByCreatedAtDesc(userId)) {
            Instant at;

            try {
                at = Instant.parse(rec.createdAt);
            } catch (Exception e) {
                at = null;
            }

            past.add(new FraudRules.Past(rec.recipientKey, rec.amountInr, at, rec.status));
        }

        return FraudRules.assess(past, to.key(), amount, Instant.now());
    }

    /** What the browser sees: the same record, seen from the sender's or the receiver's side. */
    private Map<String, Object> view(PaymentRecord rec, Long viewerId) {
        boolean sent = viewerId.equals(rec.userId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paymentId", rec.paymentId);
        out.put("direction", sent ? "SENT" : "RECEIVED");
        out.put("status", rec.status);
        out.put("message", rec.message);
        out.put("method", rec.method);
        out.put("amountInr", rec.amountInr);
        out.put("amountUsd", rec.amountUsd);
        out.put("description", rec.description);
        out.put("createdAt", rec.createdAt);
        out.put("payoutStatus", rec.payoutStatus);
        out.put("payoutMessage", rec.payoutMessage);

        /* PayPal's own id of the money movement: only a confirmed payment has one. */
        out.put("transactionId", "COMPLETED".equals(rec.status) ? rec.captureId : null);

        if (sent) {
            /* Only the sender sees this, and the number is the one they typed. Older records say "Contact 98xxxxxx10". */
            String name = Phones.fullLabel(rec.recipientName, rec.recipientPhone);

            out.put("counterparty", name);
            out.put("recipientName", name);
            out.put("recipientPhone", rec.recipientPhone);
            out.put("bankName", rec.bankName);
            out.put("ifsc", rec.ifsc);
            out.put("bankLast4", rec.bankLast4);
            out.put("riskFlags", rec.riskFlags);
            out.put("orderId", rec.orderId);
            out.put("heType", rec.heType);
            out.put("heMethod", rec.heMethod);

        } else {
            UserAccount sender = users.findById(rec.userId).orElse(null);

            out.put("counterparty", sender == null ? "A Monetra user" : sender.displayName());
        }

        return out;
    }

    /** The status message column holds 200 characters. */
    private static String limit(String text) {
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        Map<String, String> out = new HashMap<>();
        out.put("error", message);
        return ResponseEntity.status(status).body(out);
    }
}
