package com.monetra;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * My bank accounts and Check Balance (Setu Account Aggregator). Every call is for the logged-in
 * user only; Setu's keys never leave the server.
 */
@RestController
@RequestMapping("/api/bank")
public class BankController {

    private final BankService bank;

    public BankController(BankService bank) {
        this.bank = bank;
    }

    /** Whether Check Balance is set up, and the accounts this user linked. */
    @GetMapping
    public ResponseEntity<?> status(HttpSession session) {
        return run(session, userId -> bank.status(userId));
    }

    /** Starts linking an account: returns the address of Setu's consent screens to open. */
    @PostMapping("/link")
    public ResponseEntity<?> link(HttpSession session) {
        String back = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + "/?bank=return";

        return run(session, userId -> bank.startLink(userId, back));
    }

    /** After the user comes back from Setu's screens: what became of the consent. */
    @PostMapping("/consents/check")
    public ResponseEntity<?> checkConsents(HttpSession session) {
        return run(session, userId -> bank.checkConsents(userId));
    }

    /** The balance of the chosen account, or of the main one. */
    @PostMapping("/balance")
    public ResponseEntity<?> balance(@RequestBody(required = false) Map<String, Object> body, HttpSession session) {
        Long accountId = null;

        if (body != null && body.get("accountId") != null && !String.valueOf(body.get("accountId")).isBlank()) {
            try {
                accountId = Long.valueOf(String.valueOf(body.get("accountId")));
            } catch (NumberFormatException e) {
                return error(400, "That account is not valid.");
            }
        }

        Long chosen = accountId;

        return run(session, userId -> bank.balance(userId, chosen));
    }

    @PostMapping("/accounts/{id}/primary")
    public ResponseEntity<?> primary(@PathVariable Long id, HttpSession session) {
        return run(session, userId -> {
            bank.setPrimary(userId, id);
            return bank.status(userId);
        });
    }

    /** Removes a linked account: its consent is revoked at Setu first. */
    @DeleteMapping("/accounts/{id}")
    public ResponseEntity<?> remove(@PathVariable Long id, HttpSession session) {
        return run(session, userId -> {
            int removed = bank.remove(userId, id);

            Map<String, Object> out = bank.status(userId);
            out.put("removed", removed);
            return out;
        });
    }

    /* ------------------------------------------------------------------ */

    private interface Call {
        Object apply(Long userId);
    }

    private ResponseEntity<?> run(HttpSession session, Call call) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return error(401, "Please log in first.");
        }

        try {
            return ResponseEntity.ok(call.apply(userId));

        } catch (BankService.BankProblem e) {
            return error(e.status, e.getMessage());
        }
    }

    private static ResponseEntity<Map<String, String>> error(int status, String message) {
        Map<String, String> out = new HashMap<>();
        out.put("error", message);
        return ResponseEntity.status(status).body(out);
    }
}
