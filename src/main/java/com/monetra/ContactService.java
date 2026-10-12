package com.monetra;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Contacts for the Send Money screen. They come from people you saved and people you recently
 * paid or were paid by. You can only find people you already dealt with: Monetra never lets one
 * user search through all the other users.
 */
@Service
public class ContactService {

    private static final int LIMIT = 25;

    private final ContactRepository contacts;
    private final PaymentRepository payments;
    private final UserAccountRepository users;

    public ContactService(
            ContactRepository contacts,
            PaymentRepository payments,
            UserAccountRepository users) {

        this.contacts = contacts;
        this.payments = payments;
        this.users = users;
    }

    /** Contacts matching the search text (a name or part of a number), best sources first. */
    public List<Map<String, Object>> search(Long userId, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String digits = q.replaceAll("\\D", "");

        Map<String, Map<String, Object>> byPhone = new LinkedHashMap<>();

        for (Contact c : contacts.findByUserIdOrderByNameAsc(userId)) {
            add(byPhone, userId, c.phoneKey, c.name, "SAVED");
        }

        for (PaymentRecord p : payments.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (p.recipientPhone != null && !"FAILED".equals(p.status) && !"CANCELLED".equals(p.status)) {
                add(byPhone, userId, p.recipientPhone, p.recipientName, "RECENT");
            }
        }

        for (PaymentRecord p : payments.findByRecipientUserIdAndStatusOrderByCreatedAtDesc(userId, "COMPLETED")) {
            users.findById(p.userId).ifPresent(u -> add(byPhone, userId, u.getPhoneKey(), u.displayName(), "RECENT"));
        }

        List<Map<String, Object>> out = new ArrayList<>();

        for (Map<String, Object> c : byPhone.values()) {
            String name = String.valueOf(c.get("name")).toLowerCase(Locale.ROOT);
            String phone = String.valueOf(c.get("phone"));

            boolean matches = q.isEmpty()
                    || name.contains(q)
                    || (!digits.isEmpty() && phone.contains(digits));

            if (matches) {
                out.add(c);

                if (out.size() >= LIMIT) {
                    break;
                }
            }
        }

        return out;
    }

    /** Saves a contact. People on Monetra are saved under their real name; others need a label. */
    public Contact save(Long userId, String phone, String label) {
        String key = Phones.key(phone);

        if (key == null) {
            throw new IllegalArgumentException("Enter a valid 10-digit mobile number.");
        }

        UserAccount found = users.findFirstByPhoneKey(key).orElse(null);

        if (found != null && found.getId().equals(userId)) {
            throw new IllegalArgumentException("That is your own number.");
        }

        String name = found != null ? found.displayName() : (label == null ? "" : label.trim());

        if (name.isEmpty()) {
            throw new IllegalArgumentException("Give this contact a name so you can find them later.");
        }

        Contact c = contacts.findByUserIdAndPhoneKey(userId, key).orElseGet(Contact::new);

        c.userId = userId;
        c.phoneKey = key;
        c.name = name.length() > 60 ? name.substring(0, 60) : name;

        return contacts.save(c);
    }

    public boolean delete(Long userId, String phone) {
        String key = Phones.key(phone);

        if (key == null) {
            return false;
        }

        Contact c = contacts.findByUserIdAndPhoneKey(userId, key).orElse(null);

        if (c == null) {
            return false;
        }

        contacts.delete(c);

        return true;
    }

    /** Finds one contact by exact name, for the AI agent. Returns null unless exactly one matches. */
    public Map<String, Object> uniqueByName(Long userId, String name) {
        List<Map<String, Object>> matches = search(userId, name);

        List<Map<String, Object>> exact = new ArrayList<>();

        for (Map<String, Object> m : matches) {
            if (String.valueOf(m.get("name")).equalsIgnoreCase(name.trim())) {
                exact.add(m);
            }
        }

        if (exact.size() == 1) {
            return exact.get(0);
        }

        return matches.size() == 1 ? matches.get(0) : null;
    }

    private void add(Map<String, Map<String, Object>> byPhone, Long userId, String phone, String name, String source) {
        if (phone == null || phone.length() != 10 || byPhone.containsKey(phone)) {
            return;
        }

        UserAccount account = users.findFirstByPhoneKey(phone).orElse(null);

        if (account != null && account.getId().equals(userId)) {
            return;
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", account != null ? account.displayName() : name);
        m.put("phone", phone);
        m.put("maskedPhone", Phones.mask(phone));
        m.put("source", source);
        m.put("onMonetra", account != null);

        byPhone.put(phone, m);
    }
}
