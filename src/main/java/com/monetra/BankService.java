package com.monetra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * My bank accounts and Check Balance, through Setu's Account Aggregator.
 *
 * Linking: Monetra asks Setu for a consent and sends the user to Setu's own screens, where they choose
 * their bank, verify with an OTP and approve. Monetra never sees a bank password, PIN or OTP.
 *
 * Balance: under an approved consent Monetra asks for the account's summary (a "data session") and
 * shows the currentBalance the bank returned, with the time it arrived. A balance is only ever shown
 * when the bank returned one; when a check fails, the last real one stays, marked as possibly out of
 * date. Consents allow at most one data request an hour, so Monetra never asks more often than that.
 */
@Service
public class BankService {

    private static final Logger log = LoggerFactory.getLogger(BankService.class);

    /** After a failed check, wait this long before asking Setu again. */
    private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(2);

    private final SetuAaClient setu;
    private final BankLinkRepository links;
    private final BankAccountRepository accounts;
    private final UserAccountRepository users;

    @Value("${setu.aa.min-refresh-minutes:60}")
    private long minRefreshMinutes;

    @Value("${setu.aa.vua-handle:}")
    private String vuaHandle;

    /** How long one balance check waits for the bank, and how often it looks. */
    @Value("${setu.aa.wait-seconds:25}")
    private long waitSeconds;

    @Value("${setu.aa.poll-millis:1500}")
    private long pollMillis;

    /** When each consent last failed, so a failing gateway is not asked again straight away. */
    private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();

    /** One balance check at a time per user, so two clicks never use up two of the hour's requests. */
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    public BankService(SetuAaClient setu, BankLinkRepository links, BankAccountRepository accounts, UserAccountRepository users) {
        this.setu = setu;
        this.links = links;
        this.accounts = accounts;
        this.users = users;
    }

    /** Something the user can act on, with the HTTP status the page should get. */
    public static class BankProblem extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public final int status;

        BankProblem(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /* ------------------------------------------------------------------ */
    /* What is linked                                                      */
    /* ------------------------------------------------------------------ */

    public Map<String, Object> status(Long userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", setu.configured());
        out.put("missing", setu.missing());
        out.put("accounts", accountViews(userId));
        out.put("pendingConsents", links.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(l -> "PENDING".equals(l.status)).count());
        out.put("autoRefreshMinutes", minRefreshMinutes);
        return out;
    }

    /** Starts linking: creates a consent and returns the address of Setu's consent screens. */
    public Map<String, Object> startLink(Long userId, String redirectUrl) {
        requireConfigured();

        UserAccount user = users.findById(userId).orElseThrow(() -> new BankProblem(401, "Please log in first."));

        String phone = user.getPhoneKey();

        if (phone == null || phone.isBlank()) {
            throw new BankProblem(400, "Add your mobile number to your Monetra profile first: banks find your accounts by it.");
        }

        Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant from = to.minus(365, ChronoUnit.DAYS);

        Map<String, Object> consent;

        try {
            consent = setu.createConsent(vuaHandle.isBlank() ? phone : phone + "@" + vuaHandle, redirectUrl, from, to);

        } catch (HttpStatusCodeException e) {
            throw new BankProblem(502, "Setu did not start the link: " + SetuAaClient.errorSummary(e));

        } catch (RestClientException | IllegalStateException e) {
            throw new BankProblem(502, "Could not reach Setu right now. Try again in a minute.");
        }

        String id = text(consent.get("id"));
        String url = text(consent.get("url"));

        if (id.isEmpty() || url.isEmpty()) {
            throw new BankProblem(502, "Setu's reply had no consent link, so nothing was linked.");
        }

        BankLink link = new BankLink();
        link.userId = userId;
        link.consentId = id;
        link.status = consentStatus(consent.get("status"));
        link.rangeFrom = from;
        link.rangeTo = to;
        link.sandbox = setu.sandbox();

        links.save(link);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        out.put("consentId", id);
        return out;
    }

    /**
     * Asks Setu about every consent that is still waiting, for when the user comes back from Setu's
     * screens. An approved consent's accounts are added; nothing is added for any other answer.
     */
    public Map<String, Object> checkConsents(Long userId) {
        requireConfigured();

        List<String> messages = new ArrayList<>();

        for (BankLink link : links.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (!"PENDING".equals(link.status)) {
                continue;
            }

            try {
                Map<String, Object> consent = setu.getConsent(link.consentId);

                link.status = consentStatus(consent.get("status"));

                if ("ACTIVE".equals(link.status)) {
                    for (Object o : list(consent.get("accountsLinked"))) {
                        Map<String, Object> a = map(o);

                        remember(userId, link, text(a.get("linkRefNumber")), text(a.get("maskedAccNumber")),
                                text(first(a, "fipId", "fipID")), text(first(a, "accType", "type")));
                    }

                    messages.add("Your bank account was linked.");

                } else if ("REJECTED".equals(link.status)) {
                    messages.add("The consent was declined on Setu's screens, so no account was linked.");

                } else if ("PENDING".equals(link.status)) {
                    messages.add("The consent is not approved yet. Finish the steps on Setu's screens, then come back.");
                }

                link.lastError = null;

            } catch (HttpStatusCodeException e) {
                link.lastError = clip("Setu: " + SetuAaClient.errorSummary(e));
                messages.add("Could not check the consent: " + SetuAaClient.errorSummary(e));

            } catch (RestClientException | IllegalStateException e) {
                messages.add("Could not reach Setu to check the consent. Try again in a minute.");
            }

            links.save(link);
        }

        Map<String, Object> out = status(userId);
        out.put("messages", messages);
        return out;
    }

    public void setPrimary(Long userId, Long accountId) {
        BankAccount chosen = accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new BankProblem(404, "That account is not linked."));

        for (BankAccount a : accounts.findByUserIdOrderByIdAsc(userId)) {
            a.primaryAccount = a.id.equals(chosen.id);
            accounts.save(a);
        }
    }

    /**
     * Removes an account by revoking its consent at Setu, so Monetra can no longer read it. Every
     * account under the same consent goes with it. Nothing is removed if Setu could not be told.
     */
    public int remove(Long userId, Long accountId) {
        BankAccount account = accounts.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> new BankProblem(404, "That account is not linked."));

        BankLink link = links.findByUserIdAndConsentId(userId, account.consentId).orElse(null);

        if (link != null && ("ACTIVE".equals(link.status) || "PAUSED".equals(link.status))) {
            requireConfigured();

            try {
                setu.revokeConsent(link.consentId);

            } catch (HttpStatusCodeException e) {
                throw new BankProblem(502, "Setu did not revoke the consent: " + SetuAaClient.errorSummary(e)
                        + ". The account is still linked.");

            } catch (RestClientException | IllegalStateException e) {
                throw new BankProblem(502, "Could not reach Setu to revoke the consent. The account is still linked.");
            }
        }

        List<BankAccount> gone = accounts.findByUserIdAndConsentId(userId, account.consentId);

        accounts.deleteAll(gone);

        if (link != null) {
            links.delete(link);
        }

        /* The first account left becomes the main one. */
        List<BankAccount> left = accounts.findByUserIdOrderByIdAsc(userId);

        if (!left.isEmpty() && left.stream().noneMatch(a -> a.primaryAccount)) {
            left.get(0).primaryAccount = true;
            accounts.save(left.get(0));
        }

        return gone.size();
    }

    /* ------------------------------------------------------------------ */
    /* Check Balance                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * The balance of one account (the main one when none is named). A new one is asked from the bank
     * when the consent allows it: on the first check, and after that once every refresh interval.
     * Otherwise the last balance the bank returned is shown with the time it arrived.
     */
    public Map<String, Object> balance(Long userId, Long accountId) {
        synchronized (locks.computeIfAbsent(userId, k -> new Object())) {
            List<BankAccount> all = accounts.findByUserIdOrderByIdAsc(userId);

            if (all.isEmpty() && setu.configured()
                    && links.findByUserIdOrderByCreatedAtDesc(userId).stream().anyMatch(l -> "PENDING".equals(l.status))) {

                /* The user may have just approved on Setu's screens. */
                checkConsents(userId);

                all = accounts.findByUserIdOrderByIdAsc(userId);
            }

            /* An account that is not this user's is "not linked", whoever it belongs to. */
            if (accountId != null && all.stream().noneMatch(a -> a.id.equals(accountId))) {
                throw new BankProblem(404, "That account is not linked.");
            }

            if (all.isEmpty()) {
                return answer(userId, null, setu.configured() ? "NO_ACCOUNT" : "NOT_CONFIGURED",
                        setu.configured()
                                ? "No bank account is linked yet. Link one to check its balance."
                                : "Check Balance is not set up on this server yet: it needs Setu Account Aggregator keys ("
                                        + String.join(", ", setu.missing()) + ").");
            }

            BankAccount account = all.stream()
                    .filter(a -> accountId == null ? a.primaryAccount : a.id.equals(accountId))
                    .findFirst()
                    .orElse(accountId == null ? all.get(0) : null);

            if (account == null) {
                throw new BankProblem(404, "That account is not linked.");
            }

            BankLink link = links.findByUserIdAndConsentId(userId, account.consentId).orElse(null);

            if (link == null || !"ACTIVE".equals(link.status)) {
                String status = link == null ? "REVOKED" : link.status;

                return answer(userId, account, "CONSENT_" + status, switch (status) {
                    case "PENDING" -> "The consent for this account is not approved yet. Finish the steps on Setu's screens.";
                    case "PAUSED" -> "The consent for this account is paused, so its balance cannot be checked. Resume it in your Account Aggregator app.";
                    case "EXPIRED" -> "The consent for this account has expired. Link the account again to keep checking its balance.";
                    default -> "Monetra no longer has consent to read this account. Link it again to check its balance.";
                });
            }

            if (!setu.configured()) {
                return answer(userId, account, "NOT_CONFIGURED",
                        "Check Balance is not set up on this server: it needs " + String.join(", ", setu.missing()) + ".");
            }

            Instant now = Instant.now();

            Instant failed = lastFailure.get(link.consentId);

            boolean due = account.lastCheckedAt == null
                    || !now.isBefore(account.lastCheckedAt.plus(Duration.ofMinutes(minRefreshMinutes)))
                    || link.pendingSessionId != null;

            if (!due) {
                return answer(userId, account, "OK", null);
            }

            if (failed != null && now.isBefore(failed.plus(RETRY_AFTER_FAILURE)) && link.pendingSessionId == null) {
                return answer(userId, account, "ERROR", clip(link.lastError == null
                        ? "The last check did not work. Monetra will try again in a few minutes."
                        : link.lastError));
            }

            return fetch(userId, link, account);
        }
    }

    /** Asks the bank for the accounts under this consent and keeps each balance it returns. */
    private Map<String, Object> fetch(Long userId, BankLink link, BankAccount account) {
        Instant started = Instant.now();

        try {
            String sessionId = link.pendingSessionId;

            if (sessionId == null) {
                Map<String, Object> created = setu.createSession(link.consentId, link.rangeFrom, link.rangeTo);

                sessionId = text(created.get("id"));

                if (sessionId.isEmpty()) {
                    return failed(userId, link, account, "Setu did not start the balance request.");
                }
            }

            Map<String, Object> session = Map.of();
            String status = "PENDING";

            long deadline = System.currentTimeMillis() + waitSeconds * 1000;

            while (true) {
                session = setu.getSession(sessionId);
                status = text(session.get("status")).toUpperCase();

                if (!"PENDING".equals(status) || System.currentTimeMillis() >= deadline) {
                    break;
                }

                Thread.sleep(pollMillis);
            }

            if ("PENDING".equals(status)) {
                /* Not ready yet: keep the request, so the next check reads it instead of asking again. */
                link.pendingSessionId = sessionId;
                links.save(link);

                return answer(userId, account, "WAITING",
                        "The bank is still preparing the balance. Monetra will look again in a moment.");
            }

            link.pendingSessionId = null;

            if (!"COMPLETED".equals(status) && !"PARTIAL".equals(status)) {
                return failed(userId, link, account, "The bank could not send the balance (" + status.toLowerCase() + ").");
            }

            keepBalances(userId, link, session);

            /* Only a balance that arrived in this reply counts as a new one for this account. */

            BankAccount fresh = accounts.findById(account.id).orElse(account);

            if (fresh.lastCheckedAt == null || fresh.lastCheckedAt.isBefore(started)) {
                return failed(userId, link, fresh, "The bank's reply had no balance for this account.");
            }

            link.lastError = null;
            links.save(link);

            lastFailure.remove(link.consentId);

            return answer(userId, fresh, "OK", null);

        } catch (HttpStatusCodeException e) {
            String why = SetuAaClient.errorSummary(e);

            if (why.contains("frequency")) {
                return failed(userId, link, account,
                        "The consent allows only a limited number of balance checks; the next one will be possible later.");
            }

            if (why.toLowerCase().contains("expired")) {
                link.status = "EXPIRED";
            }

            return failed(userId, link, account, "Setu: " + why);

        } catch (RestClientException | IllegalStateException e) {
            log.warn("Balance check failed: {}", e.getMessage());

            return failed(userId, link, account, "Could not reach Setu right now. Your last balance is shown.");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return failed(userId, link, account, "The balance check was stopped.");
        }
    }

    /** Reads every account in a data session and keeps the balance the bank returned for it. */
    private int keepBalances(Long userId, BankLink link, Map<String, Object> session) {
        int updated = 0;

        for (Object f : list(session.get("fips"))) {
            Map<String, Object> fip = map(f);

            String fipId = text(first(fip, "fipID", "fipId"));

            for (Object o : list(fip.get("accounts"))) {
                Map<String, Object> acc = map(o);

                Map<String, Object> data = map(acc.get("data"));
                Map<String, Object> account = map(first(data, "account", "Account"));

                if (account.isEmpty()) {
                    account = map(map(data.get("decryptedFI")).get("account"));
                }

                Map<String, Object> summary = map(first(account, "summary", "Summary"));

                String masked = text(first(acc, "maskedAccNumber"));

                if (masked.isEmpty()) {
                    masked = text(account.get("maskedAccNumber"));
                }

                BankAccount row = remember(userId, link, text(acc.get("linkRefNumber")), masked, fipId,
                        text(first(summary, "type", "accountType")));

                BigDecimal balance = decimal(summary.get("currentBalance"));

                if (row == null || balance == null) {
                    continue;
                }

                row.balance = balance.setScale(2, RoundingMode.HALF_UP);
                row.currency = text(summary.get("currency")).isEmpty() ? "INR" : clip(text(summary.get("currency")), 8);
                row.balanceDateTime = clip(text(summary.get("balanceDateTime")), 40);
                row.lastCheckedAt = Instant.now();
                row.sandbox = link.sandbox;

                accounts.save(row);

                updated++;
            }
        }

        return updated;
    }

    /** Finds the account by its reference (or its masked number), or adds it. The first one is the main one. */
    private BankAccount remember(Long userId, BankLink link, String linkRef, String masked, String fipId, String type) {
        if (linkRef.isEmpty() && masked.isEmpty()) {
            return null;
        }

        List<BankAccount> mine = accounts.findByUserIdOrderByIdAsc(userId);

        Optional<BankAccount> found = mine.stream()
                .filter(a -> a.consentId.equals(link.consentId)
                        && (!linkRef.isEmpty() ? linkRef.equals(a.linkRefNumber) : masked.equals(a.maskedAccNumber)))
                .findFirst();

        BankAccount a = found.orElseGet(BankAccount::new);

        if (a.id == null) {
            a.userId = userId;
            a.consentId = link.consentId;
            a.linkRefNumber = linkRef.isEmpty() ? masked : clip(linkRef, 80);
            a.primaryAccount = mine.isEmpty();
            a.sandbox = link.sandbox;
        }

        if (!masked.isEmpty()) {
            a.maskedAccNumber = clip(masked, 40);
        }

        if (!fipId.isEmpty()) {
            a.fipId = clip(fipId, 80);
        }

        if (!type.isEmpty()) {
            a.accountType = clip(type.toUpperCase(), 20);
        }

        return accounts.save(a);
    }

    private Map<String, Object> failed(Long userId, BankLink link, BankAccount account, String why) {
        link.lastError = clip(why);
        links.save(link);

        lastFailure.put(link.consentId, Instant.now());

        return answer(userId, accounts.findById(account.id).orElse(account), "ERROR", why);
    }

    /**
     * What the Check Balance screen shows. "stale" is true when the shown balance is an older one
     * because this check did not get a new one.
     */
    private Map<String, Object> answer(Long userId, BankAccount account, String state, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", state);
        out.put("message", message);
        out.put("account", account == null ? null : view(account));
        out.put("accounts", accountViews(userId));
        out.put("stale", account != null && account.balance != null && !"OK".equals(state));

        if (account != null && account.lastCheckedAt != null) {
            out.put("nextUpdateAt", account.lastCheckedAt.plus(Duration.ofMinutes(minRefreshMinutes)).toString());
        }

        out.put("autoRefreshMinutes", minRefreshMinutes);
        return out;
    }

    private List<Map<String, Object>> accountViews(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();

        for (BankAccount a : accounts.findByUserIdOrderByIdAsc(userId)) {
            out.add(view(a));
        }

        return out;
    }

    private static Map<String, Object> view(BankAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("bank", a.fipId == null ? "Your bank" : a.fipId);
        m.put("maskedAccNumber", a.maskedAccNumber);
        m.put("accountType", a.accountType);
        m.put("primary", a.primaryAccount);
        m.put("balance", a.balance);
        m.put("currency", a.currency);
        m.put("balanceDateTime", a.balanceDateTime);
        m.put("lastCheckedAt", a.lastCheckedAt == null ? null : a.lastCheckedAt.toString());

        /* Kept apart, so a sandbox (test) balance is never passed off as a real bank balance. */
        m.put("source", a.sandbox ? "SETU_SANDBOX" : "SETU_AA");
        return m;
    }

    /* ------------------------------------------------------------------ */

    private void requireConfigured() {
        if (!setu.configured()) {
            throw new BankProblem(503, "Linking a bank account is not set up on this server yet. It needs Setu Account "
                    + "Aggregator keys in the start script: " + String.join(", ", setu.missing()) + ".");
        }
    }

    /** Setu's word for an approved consent is ACTIVE; an "APPROVED" from a notification means the same. */
    private static String consentStatus(Object value) {
        String s = text(value).toUpperCase();

        return switch (s) {
            case "ACTIVE", "APPROVED" -> "ACTIVE";
            case "REJECTED", "REVOKED", "PAUSED", "EXPIRED" -> s;
            default -> "PENDING";
        };
    }

    private static Object first(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            if (map.get(key) != null) {
                return map.get(key);
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }

    private static BigDecimal decimal(Object value) {
        try {
            return value == null ? null : new BigDecimal(String.valueOf(value).trim());

        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String clip(String s) {
        return clip(s, 200);
    }

    private static String clip(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
