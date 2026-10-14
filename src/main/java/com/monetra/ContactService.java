package com.monetra;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Contacts for the Send Money screen. They come from people you saved, people you recently
 * paid or were paid by, and your own phone's contact list if you add it. You can only find
 * people from those lists: Monetra never lets one user search through all the other users.
 */
@Service
public class ContactService {

    private static final int LIMIT = 25;

    static final int MAX_IMPORT = 2000;
    static final int MAX_CONTACTS = 5000;

    /** One contact while the list is being put together. */
    private static final class Entry {
        String name;
        String source;
        boolean saved;
    }

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

    /**
     * Contacts matching the search text (a name or part of a number). Shown in this order:
     * saved by hand, then recent, then the phone's contact list.
     */
    public List<Map<String, Object>> search(Long userId, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String digits = q.replaceAll("\\D", "");

        List<Contact> rows = contacts.findByUserIdOrderByNameAsc(userId);

        Map<String, Contact> saved = new HashMap<>();

        for (Contact c : rows) {
            saved.put(c.phoneKey, c);
        }

        Map<String, Entry> byPhone = new LinkedHashMap<>();

        for (Contact c : rows) {
            if (!Contact.PHONE.equals(c.source)) {
                add(byPhone, saved, c.phoneKey, c.name, "SAVED");
            }
        }

        for (PaymentRecord p : payments.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (p.recipientPhone != null && !"FAILED".equals(p.status) && !"CANCELLED".equals(p.status)) {
                add(byPhone, saved, p.recipientPhone, p.recipientName, "RECENT");
            }
        }

        for (PaymentRecord p : payments.findByRecipientUserIdAndStatusOrderByCreatedAtDesc(userId, "COMPLETED")) {
            users.findById(p.userId).ifPresent(u -> add(byPhone, saved, u.getPhoneKey(), u.displayName(), "RECENT"));
        }

        for (Contact c : rows) {
            if (Contact.PHONE.equals(c.source)) {
                add(byPhone, saved, c.phoneKey, c.name, Contact.PHONE);
            }
        }

        Map<String, UserAccount> accounts = accountsFor(new ArrayList<>(byPhone.keySet()));

        List<Map<String, Object>> out = new ArrayList<>();

        for (Map.Entry<String, Entry> e : byPhone.entrySet()) {
            String phone = e.getKey();
            Entry entry = e.getValue();
            UserAccount account = accounts.get(phone);

            if (account != null && account.getId().equals(userId)) {
                continue;
            }

            /* Your own name for a contact is kept. Someone you only paid is shown by their Monetra name. */
            String name = account != null && !entry.saved ? account.displayName() : entry.name;
            String onMonetraAs = account != null && !account.displayName().equalsIgnoreCase(name)
                    ? account.displayName() : null;

            boolean matches = q.isEmpty()
                    || name.toLowerCase(Locale.ROOT).contains(q)
                    || (onMonetraAs != null && onMonetraAs.toLowerCase(Locale.ROOT).contains(q))
                    || (!digits.isEmpty() && phone.contains(digits));

            if (!matches) {
                continue;
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("phone", phone);
            m.put("maskedPhone", Phones.mask(phone));
            m.put("source", entry.source);
            m.put("saved", entry.saved);
            m.put("onMonetra", account != null);

            if (onMonetraAs != null) {
                m.put("monetraName", onMonetraAs);
            }

            out.add(m);

            if (out.size() >= LIMIT) {
                break;
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
        c.name = clip(name);
        c.source = null;

        return contacts.save(c);
    }

    /**
     * Adds many contacts at once, from the phone's contact list. Each item needs a name and a phone.
     * Contacts you saved by hand are left as they are. Returns how many were added, updated and skipped.
     */
    @Transactional
    public Map<String, Object> importFromPhone(Long userId, List<?> items) {
        if (items.size() > MAX_IMPORT) {
            throw new IllegalArgumentException("Send at most " + MAX_IMPORT + " contacts at a time.");
        }

        String own = users.findById(userId).map(u -> u.getPhoneKey()).orElse("");

        Map<String, Contact> existing = new HashMap<>();

        for (Contact c : contacts.findByUserIdOrderByNameAsc(userId)) {
            existing.put(c.phoneKey, c);
        }

        Set<String> seen = new HashSet<>();
        List<Contact> changed = new ArrayList<>();

        int added = 0;
        int updated = 0;
        int skipped = 0;

        for (Object o : items) {
            String key = null;
            String name = "";

            if (o instanceof Map<?, ?> m) {
                key = Phones.key(m.get("phone") == null ? null : String.valueOf(m.get("phone")));
                name = m.get("name") == null ? "" : clip(String.valueOf(m.get("name")).replaceAll("\\p{Cntrl}", " ").trim());
            }

            /* Indian mobile numbers start with 6 to 9. Landlines and unnamed entries are left out. */
            if (key == null || key.charAt(0) < '6' || name.isEmpty() || key.equals(own) || !seen.add(key)) {
                skipped++;
                continue;
            }

            Contact c = existing.get(key);

            if (c == null) {
                if (existing.size() >= MAX_CONTACTS) {
                    skipped++;
                    continue;
                }

                c = new Contact();
                c.userId = userId;
                c.phoneKey = key;
                c.name = name;
                c.source = Contact.PHONE;

                existing.put(key, c);
                changed.add(c);
                added++;

            } else if (Contact.PHONE.equals(c.source) && !c.name.equals(name)) {
                c.name = name;
                changed.add(c);
                updated++;

            } else {
                skipped++;
            }
        }

        contacts.saveAll(changed);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("added", added);
        out.put("updated", updated);
        out.put("skipped", skipped);
        return out;
    }

    /** Removes every contact that came from the phone's contact list. Returns how many were removed. */
    public int removeImported(Long userId) {
        List<Contact> imported = contacts.findByUserIdAndSource(userId, Contact.PHONE);

        contacts.deleteAll(imported);

        return imported.size();
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

    /** Adds a number once. If you have your own contact for it, your name for that person is used. */
    private static void add(Map<String, Entry> byPhone, Map<String, Contact> saved, String phone, String name, String source) {
        if (phone == null || phone.length() != 10 || byPhone.containsKey(phone)) {
            return;
        }

        Contact own = saved.get(phone);

        Entry e = new Entry();
        e.name = own != null ? own.name : (name == null ? "" : name);
        e.source = source;
        e.saved = own != null;

        byPhone.put(phone, e);
    }

    /** The Monetra accounts behind these numbers, found with a few queries instead of one per number. */
    private Map<String, UserAccount> accountsFor(List<String> phones) {
        Map<String, UserAccount> out = new HashMap<>();

        for (int i = 0; i < phones.size(); i += 500) {
            for (UserAccount u : users.findByPhoneKeyIn(phones.subList(i, Math.min(i + 500, phones.size())))) {
                out.putIfAbsent(u.getPhoneKey(), u);
            }
        }

        return out;
    }

    private static String clip(String name) {
        return name.length() > 60 ? name.substring(0, 60) : name;
    }
}
