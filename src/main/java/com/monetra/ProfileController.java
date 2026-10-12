package com.monetra;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** The logged-in user's profile, including the PayPal email that payments are paid out to. */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final UserAccountRepository users;
    private final PaymentService payments;

    public ProfileController(UserAccountRepository users, PaymentService payments) {
        this.users = users;
        this.payments = payments;
    }

    @GetMapping
    public ResponseEntity<?> get(HttpSession session) {
        UserAccount user = current(session);

        if (user == null) {
            return unauthorized();
        }

        return ResponseEntity.ok(view(user));
    }

    /** Saves the PayPal email (or clears it with an empty value) and pays out anything that was waiting. */
    @PutMapping("/paypal-email")
    public ResponseEntity<?> setPaypalEmail(@RequestBody Map<String, Object> body, HttpSession session) {
        UserAccount user = current(session);

        if (user == null) {
            return unauthorized();
        }

        String email = body.get("email") == null ? "" : String.valueOf(body.get("email")).trim();

        if (!email.isEmpty() && (email.length() > 120 || !EMAIL.matcher(email).matches())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter a valid PayPal email address."));
        }

        user.setPaypalEmail(email);

        users.save(user);

        if (user.getPaypalEmail() != null) {
            payments.retryWaitingPayouts(user.getId());
        }

        return ResponseEntity.ok(view(user));
    }

    private UserAccount current(HttpSession session) {
        Long id = SessionUser.id(session);

        return id == null ? null : users.findById(id).orElse(null);
    }

    private static Map<String, Object> view(UserAccount user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", user.getEmail());
        out.put("name", user.displayName());
        out.put("phone", user.getPhoneKey() == null ? "" : user.getPhoneKey());
        out.put("paypalEmail", user.getPaypalEmail() == null ? "" : user.getPaypalEmail());
        return out;
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in first."));
    }
}
