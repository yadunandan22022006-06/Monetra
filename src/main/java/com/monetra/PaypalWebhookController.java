package com.monetra;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Receives PayPal webhooks. A payment can complete even if the browser was closed, and a
 * payout finishes later, so PayPal tells us. Every event is verified with PayPal first,
 * and handling an event twice is harmless.
 *
 * Set PAYPAL_WEBHOOK_ID to the id of the webhook you create in the PayPal developer dashboard
 * (URL: https://your-server/api/paypal/webhook). Without it, this endpoint refuses events.
 */
@RestController
@RequestMapping("/api/paypal")
public class PaypalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaypalWebhookController.class);

    private final PaypalClient paypal;
    private final PaymentService service;
    private final PaymentRepository payments;
    private final ObjectMapper mapper = new ObjectMapper();

    public PaypalWebhookController(PaypalClient paypal, PaymentService service, PaymentRepository payments) {
        this.paypal = paypal;
        this.service = service;
        this.payments = payments;
    }

    @PostMapping("/webhook")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> webhook(@RequestBody String raw, @RequestHeader Map<String, String> headers) {

        if (!paypal.webhookConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Webhooks are not configured (set PAYPAL_WEBHOOK_ID)."));
        }

        Map<String, Object> event;

        try {
            event = mapper.readValue(raw, Map.class);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Not a PayPal event."));
        }

        try {
            if (!paypal.verifyWebhook(headers, event)) {
                log.warn("Rejected a webhook that failed PayPal's signature check");

                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Signature check failed."));
            }

        } catch (RuntimeException e) {
            log.error("Could not verify the webhook with PayPal", e);

            /* PayPal will retry later. */
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Could not verify the event."));
        }

        String type = PaypalClient.text(event.get("event_type"));
        Map<String, Object> resource = PaypalClient.map(event.get("resource"));

        try {
            if ("PAYMENT.CAPTURE.COMPLETED".equals(type)) {
                captureCompleted(resource);

            } else if (type.startsWith("PAYMENT.PAYOUTS-ITEM.")) {
                payoutItem(type, resource);
            }

        } catch (RuntimeException e) {
            log.error("Could not handle webhook {}", type, e);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Could not handle the event."));
        }

        return ResponseEntity.ok(Map.of("received", true));
    }

    /** The money was captured: finish the payment even if the browser never came back. */
    private void captureCompleted(Map<String, Object> resource) {
        Map<String, Object> related = PaypalClient.map(
                PaypalClient.map(resource.get("supplementary_data")).get("related_ids"));

        String orderId = PaypalClient.text(related.get("order_id"));

        if (orderId.isEmpty()) {
            return;
        }

        PaymentRecord found = payments.findByOrderId(orderId).orElse(null);

        if (found == null) {
            return;
        }

        synchronized (service.lock(found.paymentId)) {
            PaymentRecord rec = payments.findById(found.paymentId).orElse(found);

            if ("COMPLETED".equals(rec.status)) {
                return;   /* already done: PayPal sends events more than once */
            }

            Map<String, Object> amount = PaypalClient.map(resource.get("amount"));

            boolean matches;

            try {
                matches = "USD".equals(PaypalClient.text(amount.get("currency_code")))
                        && new BigDecimal(PaypalClient.text(amount.get("value"))).compareTo(rec.amountUsd) == 0;
            } catch (NumberFormatException e) {
                matches = false;
            }

            if (!matches) {
                log.warn("Webhook capture for payment {} did not match the amount we created", rec.paymentId);
                return;
            }

            service.completed(rec, PaypalClient.text(resource.get("id")));
        }
    }

    /** The receiver's PayPal payout moved forward (sent, failed, held). */
    private void payoutItem(String type, Map<String, Object> resource) {
        String paymentId = PaypalClient.text(resource.get("sender_item_id"));

        if (paymentId.isEmpty()) {
            paymentId = PaypalClient.text(PaypalClient.map(resource.get("payout_item")).get("sender_item_id"));
        }

        if (paymentId.isEmpty()) {
            return;
        }

        PaymentRecord found = payments.findById(paymentId).orElse(null);

        if (found == null) {
            return;
        }

        synchronized (service.lock(paymentId)) {
            PaymentRecord rec = payments.findById(paymentId).orElse(found);

            String status = PaymentService.itemStatus(type);

            service.applyPayoutStatus(rec, status,
                    "SENT".equals(status) ? "Sent to the receiver's PayPal account."
                            : ("PENDING".equals(status)
                            ? "PayPal is holding the payout for the receiver."
                            : "PayPal could not complete the payout (" + type.substring(type.lastIndexOf('.') + 1).toLowerCase() + ")."));
        }
    }
}
