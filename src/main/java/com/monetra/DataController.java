package com.monetra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * Saves and loads the logged-in user's dashboard records, and runs the
 * server-side budget check used before a payment.
 */
@RestController
@RequestMapping("/api")
public class DataController {

    private static final int MAX_JSON_CHARS = 500_000;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");
    private static final String EMPTY = "{}";

    private final UserDataRepository store;
    private final ObjectMapper mapper = new ObjectMapper();

    public DataController(UserDataRepository store) {
        this.store = store;
    }

    @GetMapping(value = "/data", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> load(HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(savedJson(userId));
    }

    @PutMapping("/data")
    public ResponseEntity<?> save(@RequestBody String body, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        if (body == null || body.length() > MAX_JSON_CHARS) {
            return ResponseEntity.badRequest().body(Map.of("error", "Data is too large."));
        }

        try {
            JsonNode node = mapper.readTree(body);

            if (node == null || !node.isObject()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid data."));
            }

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid data."));
        }

        UserData row = store.findById(userId).orElse(null);

        if (row == null) {
            store.save(new UserData(userId, body));
        } else {
            row.setJson(body);
            store.save(row);
        }

        return ResponseEntity.ok(Map.of("saved", true));
    }

    /** Budget check calculated from the user's SAVED records, not from the request. */
    @PostMapping("/guardian/check")
    public ResponseEntity<?> check(@RequestBody Map<String, Object> body, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        BigDecimal amount;

        try {
            amount = new BigDecimal(String.valueOf(body.get("amountInr")));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter a valid amount."));
        }

        if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "Enter a valid amount."));
        }

        JsonNode data = null;

        try {
            data = mapper.readTree(savedJson(userId));

        } catch (Exception e) {
            data = null;
        }

        return ResponseEntity.ok(BudgetRules.check(data, amount, LocalDate.now()));
    }

    private String savedJson(Long userId) {
        UserData row = store.findById(userId).orElse(null);

        return row == null ? EMPTY : row.getJson();
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in first."));
    }
}
