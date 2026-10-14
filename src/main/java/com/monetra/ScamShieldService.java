package com.monetra;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scam Shield: checks a message, a payment request or a link the user pasted for the tricks that
 * payment scams in India use, and says how risky it looks and why.
 *
 * Plain Java rules, so the result is the same every time and can be explained line by line. The
 * message is only read here: it is not saved, logged or sent to the AI.
 */
@Service
public class ScamShieldService {

    /** The longest message that is checked. */
    public static final int MAX_LENGTH = 2000;

    private static final int HIGH = 6;
    private static final int MEDIUM = 3;

    /** "Do not share this OTP": a warning in a genuine message is not a request. */
    private static final Pattern NEGATED = Pattern.compile("\\b(do not|don't|dont|never|not to)\\s+(\\w+\\s+){0,2}$");

    /**
     * One thing scams do. All of its patterns must be found in the message. A negatable rule does
     * not count when its first pattern comes straight after "do not" or "never".
     */
    private record Rule(String id, int weight, String reason, boolean negatable, Pattern... patterns) {
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    private static final List<Rule> RULES = List.of(
            new Rule("PIN", 6,
                    "It asks for your PIN. Nobody needs your UPI PIN to send you money: entering it sends money from your account.",
                    true, p("\\b(upi\\s*pin|m-?pin|atm\\s*pin|enter (your |the )?pin|share (your |the )?pin)\\b")),

            new Rule("OTP", 6,
                    "It asks you to share an OTP or a code. Banks and companies never ask for it.",
                    true, p("\\b(share|send|tell|give|forward|read out)\\b[^.!?]{0,30}\\b(otp|one[- ]time password|verification code|code)\\b")),

            new Rule("PASSWORD", 5,
                    "It asks for card or login details. Never type your CVV, card number or password from a message.",
                    true, p("\\b(share|send|enter|type|give|tell|update|confirm|provide)\\b[^.!?]{0,25}"
                            + "\\b(cvv|card number|card details|net ?banking password|password|login details)\\b")),

            new Rule("RECEIVE", 5,
                    "It says you must enter a PIN, scan a code or approve a request to receive money. Receiving money never needs that: "
                            + "this is how collect-request and QR scams take money from you.",
                    false, p("\\b(receive|get|claim)\\b[^.!?]{0,25}\\b(money|payment|amount|refund|cashback|prize)\\b"),
                    p("\\b(pin|scan|approve|accept)\\b")),

            new Rule("QR", 5,
                    "It asks you to scan a QR code to get money. Scanning a QR code only ever pays someone; it never pays you.",
                    false, p("\\bscan\\b[^.!?]{0,30}\\b(qr|code)\\b"),
                    p("\\b(receive|refund|cashback|prize|reward|get (the |your )?money)\\b")),

            new Rule("REMOTE", 6,
                    "It asks you to install a screen-sharing app. With it, a scammer can watch you type your PIN and passwords.",
                    false, p("\\b(anydesk|teamviewer|quick ?support|rustdesk|airdroid|screen ?shar(e|ing)|remote access)\\b")),

            new Rule("APK", 5,
                    "It asks you to install an app from a file (.apk). Apps from messages can read your SMS and OTPs.",
                    false, p("\\.apk\\b|\\binstall (this|the) app\\b")),

            new Rule("ARREST", 6,
                    "It talks about an arrest or a court case. Police and courts never ask for money by message or video call.",
                    false, p("\\b(digital arrest|arrest warrant|you will be arrested|case (has been |is )?(filed|registered) against you)\\b")),

            new Rule("AUTHORITY", 3,
                    "It claims to come from the police, a government office, the RBI or a courier. They never ask for money this way.",
                    false, p("\\b(police|cbi|cyber cell|customs|income tax department|rbi|trai|narcotics)\\b")),

            new Rule("ELECTRICITY", 5,
                    "It threatens to cut your electricity. Electricity boards do not send disconnection threats with a phone number or link.",
                    false, p("\\b(electricity|power supply|power connection|bijli|light bill)\\b"),
                    p("\\b(disconnect\\w*|cut off|will be cut|be cut)\\b")),

            new Rule("KYC", 4,
                    "It says your account, card or KYC will be blocked. Banks do not block accounts through a message with a link or number.",
                    false, p("\\b(kyc|pan card|aadhaar|account|card|sim)\\b"),
                    p("\\b(block\\w*|suspend\\w*|deactivat\\w*|expire\\w*|will be closed)\\b")),

            new Rule("PRIZE", 4,
                    "It says you won a prize, lottery or reward you never entered.",
                    false, p("\\b(lottery|lucky draw|jackpot|you (have )?won|winner|prize|kbc|gift voucher)\\b")),

            new Rule("JOB", 3,
                    "It offers easy money: a part-time job, tasks to earn or guaranteed returns. These ask you to pay first and never pay back.",
                    false, p("\\b(part[- ]time job|work from home|daily (income|earning)|double your money|guaranteed (return|returns|profit)"
                            + "|task(s)? (and|to) earn|like (and|&) earn|investment (plan|scheme))\\b")),

            new Rule("FAMILY", 3,
                    "Someone says they have a new number or urgently need money. Call the person on the number you already have first.",
                    false, p("\\b(new number|lost my phone|send money urgently|urgent(ly)? need (money|cash)|in (the )?hospital)\\b")),

            new Rule("URGENT", 2,
                    "It pushes you to act fast. Scammers create panic so you do not stop to check.",
                    false, p("\\b(urgent|urgently|immediately|within (24|48) hours|today itself|last (date|warning|chance)|final (notice|warning)"
                            + "|act now|expires? today|tonight)\\b")),

            new Rule("SHORT_LINK", 3,
                    "It has a shortened link that hides where it really goes.",
                    false, p("\\b(bit\\.ly|tinyurl\\.com|t\\.co|goo\\.gl|cutt\\.ly|rb\\.gy|is\\.gd|shorturl\\.at)\\b")),

            new Rule("LINK", 2,
                    "It has a link. Do not open links in messages like this: open the official app or website yourself.",
                    false, p("(https?://|www\\.)\\S+")),

            new Rule("CALL", 1,
                    "It asks you to call or message a number written in it. Use the number on the official website instead.",
                    false, p("\\b(call|contact|whatsapp|dial)\\b[^.!?]{0,15}\\+?\\d[\\d -]{8,}")));

    private static int weight(String id) {
        return RULES.stream().filter(r -> r.id().equals(id)).mapToInt(r -> r.weight()).findFirst().orElse(0);
    }

    /** How risky the message looks, why, the words that gave it away, and what to do. */
    public Map<String, Object> check(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT).replace('’', '\'');

        int score = 0;

        List<Map<String, String>> reasons = new ArrayList<>();
        Set<String> phrases = new LinkedHashSet<>();

        for (Rule rule : RULES) {
            List<String> found = new ArrayList<>();

            for (int i = 0; i < rule.patterns().length; i++) {
                Matcher m = rule.patterns()[i].matcher(text);

                String hit = null;

                while (m.find()) {
                    if (i == 0 && rule.negatable() && NEGATED.matcher(text.substring(Math.max(0, m.start() - 30), m.start())).find()) {
                        continue;
                    }

                    hit = m.group().trim();
                    break;
                }

                if (hit == null) {
                    found = null;
                    break;
                }

                found.add(hit);
            }

            if (found == null) {
                continue;
            }

            /* A shortened link is a link too: count the stronger reason once. */
            if ("LINK".equals(rule.id()) && reasons.stream().anyMatch(r -> "SHORT_LINK".equals(r.get("rule")))) {
                phrases.addAll(found);
                continue;
            }

            score += rule.weight();

            Map<String, String> reason = new LinkedHashMap<>();
            reason.put("rule", rule.id());
            reason.put("text", rule.reason());
            reasons.add(reason);

            phrases.addAll(found);
        }

        /* The most serious reason first: it is the one the result leads with. */
        reasons.sort((a, b) -> Integer.compare(weight(b.get("rule")), weight(a.get("rule"))));

        String level = score >= HIGH ? "HIGH" : score >= MEDIUM ? "MEDIUM" : "LOW";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level);
        out.put("score", score);
        out.put("reasons", reasons);
        out.put("phrases", new ArrayList<>(phrases));
        out.put("reason", reasons.isEmpty()
                ? "Nothing that payment scams usually do was found in this message."
                : reasons.get(0).get("text"));
        out.put("recommendation", switch (level) {
            case "HIGH" -> "Do not open the link, call the number, share a PIN or OTP, or pay. "
                    + "Check with the bank or company yourself, using their official app or website.";
            case "MEDIUM" -> "Be careful. Check with the person or company directly before you click, call or pay.";
            default -> "Nothing common to scams was found. If something still feels wrong, check with the sender directly before paying.";
        });
        return out;
    }
}
