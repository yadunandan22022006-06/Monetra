package com.monetra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What happens after PayPal confirms a payment: notify both people, settle a linked household
 * expense, and pay the receiver out to their PayPal account. Used by the checkout return
 * and by the PayPal webhook, so both behave the same.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final UserAccountRepository users;
    private final NotificationRepository notifications;
    private final HomeExpenseService home;
    private final PaypalClient paypal;
    private final GuardianScoreService score;

    /** One lock object per payment, so two requests for the same payment cannot overlap. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public PaymentService(
            PaymentRepository payments,
            UserAccountRepository users,
            NotificationRepository notifications,
            HomeExpenseService home,
            PaypalClient paypal,
            GuardianScoreService score) {

        this.payments = payments;
        this.users = users;
        this.notifications = notifications;
        this.home = home;
        this.paypal = paypal;
        this.score = score;
    }

    public Object lock(String paymentId) {
        return locks.computeIfAbsent(paymentId, k -> new Object());
    }

    /**
     * Call once, while holding the payment's lock, when PayPal has confirmed the capture
     * and the amount matches.
     */
    public void completed(PaymentRecord rec, String captureId) {
        rec.status = "COMPLETED";
        rec.captureId = captureId;
        rec.message = "Payment confirmed by PayPal.";

        payments.save(rec);

        notifyUser(rec.userId, "PAYMENT_SENT",
                "You sent " + rupees(rec.amountInr) + " to " + Phones.fullLabel(rec.recipientName, rec.recipientPhone) + ".",
                rec.paymentId);

        if (rec.recipientUserId != null) {
            String from = users.findById(rec.userId).map(u -> u.displayName()).orElse("someone");

            notifyUser(rec.recipientUserId, "PAYMENT_RECEIVED",
                    "You received " + rupees(rec.amountInr) + " from " + from
                            + (rec.description == null || rec.description.isBlank()
                            ? "." : " (" + rec.description + ")."), rec.paymentId);
        }

        if (rec.homeExpenseId != null && rec.homeDueDate != null) {
            try {
                home.recordPaid(rec.userId, rec.homeExpenseId, LocalDate.parse(rec.homeDueDate),
                        "PAYPAL", rec.paymentId);

            } catch (RuntimeException e) {
                log.warn("Could not link payment {} to its household expense", rec.paymentId, e);
            }
        }

        /* Only a payment PayPal confirmed reaches the Guardian Score. */
        score.refreshQuietly(rec.userId);

        payoutIfPossible(rec);
    }

    /* ------------------------------------------------------------------ */
    /* Payout to the receiver's PayPal account                             */
    /* ------------------------------------------------------------------ */

    /** Sends the money on to the receiver's PayPal email. Safe to call again: it only acts when needed. */
    public void payoutIfPossible(PaymentRecord rec) {
        String current = rec.payoutStatus;

        if (current != null && !"WAITING_EMAIL".equals(current) && !"FAILED".equals(current)) {
            return;
        }

        if ("BANK".equals(rec.method)) {
            setPayout(rec, "NONE", "Bank transfers are simulated in the Sandbox, so no bank payout is made.");
            return;
        }

        if (rec.recipientUserId == null) {
            setPayout(rec, "NONE", "The receiver is not on Monetra, so no PayPal payout is made.");
            return;
        }

        UserAccount to = users.findById(rec.recipientUserId).orElse(null);

        if (to == null || to.getPaypalEmail() == null) {
            boolean first = !"WAITING_EMAIL".equals(current);

            setPayout(rec, "WAITING_EMAIL", "Waiting for the receiver to add their PayPal email.");

            if (first && to != null) {
                String from = users.findById(rec.userId).map(u -> u.displayName()).orElse("someone");

                notifyUser(rec.recipientUserId, "PAYOUT",
                        "Add your PayPal email in Monetra Guardian to receive " + rupees(rec.amountInr)
                                + " from " + from + " in your PayPal account.", rec.paymentId);
            }

            return;
        }

        if (!paypal.configured()) {
            setPayout(rec, "FAILED", "PayPal is not configured on the server.");
            return;
        }

        try {
            Map<String, Object> reply = paypal.createPayout(
                    rec.paymentId, to.getPaypalEmail(), rec.amountUsd,
                    "Monetra payment" + (rec.description == null || rec.description.isBlank() ? "" : ": " + rec.description));

            Map<String, Object> header = PaypalClient.map(reply.get("batch_header"));

            rec.payoutBatchId = PaypalClient.text(header.get("payout_batch_id"));

            String status = batchStatus(PaypalClient.text(header.get("batch_status")));

            applyPayoutStatus(rec, status, "SENT".equals(status)
                    ? "Sent to the receiver's PayPal account."
                    : "PayPal is sending the money to the receiver's PayPal account.");

        } catch (HttpStatusCodeException e) {
            log.error("PayPal payout failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());

            applyPayoutStatus(rec, "FAILED",
                    "PayPal could not send the payout. Check that Payouts is enabled for your PayPal app and "
                            + "that the Sandbox business account has a balance.");

        } catch (RestClientException | IllegalStateException e) {
            log.error("Could not reach PayPal for the payout", e);

            applyPayoutStatus(rec, "FAILED",
                    "Could not reach PayPal for the payout. Press Check with PayPal to try again.");
        }
    }

    /** When a receiver adds their PayPal email, pay out everything that was waiting for it. */
    public void retryWaitingPayouts(Long recipientUserId) {
        for (PaymentRecord p : payments.findByRecipientUserIdAndStatusOrderByCreatedAtDesc(recipientUserId, "COMPLETED")) {
            if ("WAITING_EMAIL".equals(p.payoutStatus)) {
                synchronized (lock(p.paymentId)) {
                    PaymentRecord fresh = payments.findById(p.paymentId).orElse(p);

                    payoutIfPossible(fresh);
                }
            }
        }
    }

    /** Updates the payout status and tells people about the final result, once. */
    public void applyPayoutStatus(PaymentRecord rec, String status, String message) {
        String before = rec.payoutStatus;

        rec.payoutStatus = status;
        rec.payoutMessage = message;

        payments.save(rec);

        if (status.equals(before)) {
            return;
        }

        if ("SENT".equals(status) && rec.recipientUserId != null) {
            notifyUser(rec.recipientUserId, "PAYOUT",
                    "Your PayPal payout of " + rupees(rec.amountInr) + " was sent.", rec.paymentId);
        }

        if ("FAILED".equals(status)) {
            notifyUser(rec.userId, "PAYOUT",
                    "Your payment of " + rupees(rec.amountInr) + " to " + rec.recipientName
                            + " was confirmed, but the payout to their PayPal account did not complete.", rec.paymentId);
        }
    }

    private void setPayout(PaymentRecord rec, String status, String message) {
        rec.payoutStatus = status;
        rec.payoutMessage = message;

        payments.save(rec);
    }

    /** Maps a PayPal payout batch status to ours. */
    static String batchStatus(String paypalStatus) {
        return switch (paypalStatus) {
            case "SUCCESS" -> "SENT";
            case "DENIED", "CANCELED" -> "FAILED";
            default -> "PENDING";
        };
    }

    /** Maps a PayPal payout item event (PAYMENT.PAYOUTS-ITEM.SUCCEEDED...) to our status. */
    static String itemStatus(String eventType) {
        if (eventType.endsWith(".SUCCEEDED")) {
            return "SENT";
        }

        if (eventType.endsWith(".HELD") || eventType.endsWith(".UNCLAIMED")) {
            return "PENDING";
        }

        return "FAILED";
    }

    /* ------------------------------------------------------------------ */

    public void notifyUser(Long userId, String type, String message, String paymentId) {
        notifications.save(new Notification(userId, type, message, paymentId));
    }

    public static String rupees(BigDecimal value) {
        return Inr.of(value);
    }

    /** Used by tests. */
    List<PaymentRecord> allFor(Long userId) {
        return payments.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
