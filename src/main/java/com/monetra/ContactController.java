package com.monetra;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Search and save contacts on the Send Money screen. */
@RestController
@RequestMapping("/api/contacts")
public class ContactController {

    private final ContactService contacts;

    public ContactController(ContactService contacts) {
        this.contacts = contacts;
    }

    @GetMapping
    public ResponseEntity<?> search(@RequestParam(required = false) String q, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        return ResponseEntity.ok(contacts.search(userId, q));
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        try {
            Contact c = contacts.save(userId,
                    String.valueOf(body.get("phone")),
                    body.get("name") == null ? "" : String.valueOf(body.get("name")));

            return ResponseEntity.ok(Map.of("name", c.name, "phone", c.phoneKey));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{phone}")
    public ResponseEntity<?> delete(@PathVariable String phone, HttpSession session) {
        Long userId = SessionUser.id(session);

        if (userId == null) {
            return unauthorized();
        }

        return contacts.delete(userId, phone)
                ? ResponseEntity.ok(Map.of("deleted", true))
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Contact not found."));
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in first."));
    }
}
