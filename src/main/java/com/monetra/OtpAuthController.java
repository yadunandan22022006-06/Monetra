package com.monetra;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class OtpAuthController {


private final JavaMailSender mailSender;
private final SecureRandom random = new SecureRandom();
private final BCryptPasswordEncoder passwordEncoder =
        new BCryptPasswordEncoder();

private final Map<String, PendingUser> pendingUsers =
        new ConcurrentHashMap<>();

private final UserAccountRepository users;

private static final Logger log = LoggerFactory.getLogger(OtpAuthController.class);

private final String mailUser;
private final boolean logOtp;

public OtpAuthController(
        JavaMailSender mailSender,
        UserAccountRepository users,
        @Value("${spring.mail.username:}") String mailUser,
        @Value("${monetra.log-otp:false}") boolean logOtp) {

    this.mailSender = mailSender;
    this.users = users;
    this.mailUser = mailUser;
    this.logOtp = logOtp;
}

// STEP 1: SEND OTP TO EMAIL
@PostMapping("/send-otp")
public ResponseEntity<String> sendOtp(
        @RequestBody RegistrationRequest request) {

    if (request.email() == null
            || request.password() == null
            || request.email().isBlank()
            || request.password().length() < 8) {

        return ResponseEntity.badRequest().body(
                "Enter a valid email and a password of at least 8 characters.");
    }

    String email = request.email().trim().toLowerCase();

    if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
        return ResponseEntity.badRequest()
                .body("Enter a valid email address.");
    }

    if (users.existsByEmail(email)) {
        return ResponseEntity.badRequest()
                .body("An account already exists for this email.");
    }

    PendingUser existing = pendingUsers.get(email);

    if (existing != null
            && Instant.now().isBefore(
                    existing.lastSent.plusSeconds(60))) {

        return ResponseEntity.badRequest().body(
                "Please wait 60 seconds before requesting another OTP.");
    }

    String otp = String.format("%06d", random.nextInt(1_000_000));

    if (Phones.key(request.phone()) == null) {
        return ResponseEntity.badRequest().body(
                "Enter a valid mobile number (at least 10 digits). "
                + "Other people use it to find and pay you.");
    }

    String fullName = request.name() == null ? "" : request.name().trim();

    if (fullName.length() > 60) {
        fullName = fullName.substring(0, 60);
    }

    PendingUser pending = new PendingUser(
            passwordEncoder.encode(request.password()),
            request.phone(),
            fullName,
            otp,
            Instant.now().plusSeconds(300),
            Instant.now(),
            0
    );

    pendingUsers.put(email, pending);

    if (mailUser == null || mailUser.isBlank()) {
        if (logOtp) {
            log.warn("DEV MODE: email is not configured. The OTP for {} is {}", email, otp);

            return ResponseEntity.ok(
                    "Email is not set up on this server (dev mode), so your code is in the server console.");
        }

        pendingUsers.remove(email, pending);

        return ResponseEntity.status(503).body(
                "Email sending is not set up on the server. Set MONETRA_MAIL_USERNAME and "
                + "MONETRA_MAIL_PASSWORD (a Gmail App Password), then restart Monetra.");
    }

    try {
        SimpleMailMessage message = new SimpleMailMessage();

        message.setFrom(mailUser);
        message.setTo(email);
        message.setSubject("Your Monetra verification code");

        message.setText(
                "Your Monetra OTP is: " + otp
                + "\n\nThis code expires in 5 minutes."
                + "\nDo not share this code with anyone."
        );

        mailSender.send(message);

        return ResponseEntity.ok(
                "OTP sent to your email. It expires in 5 minutes.");

    } catch (Exception e) {
        log.error("Could not send the OTP email to {}", email, e);

        if (logOtp) {
            log.warn("DEV MODE: the OTP for {} is {}", email, otp);

            return ResponseEntity.ok(
                    "The email could not be sent (dev mode), so your code is in the server console.");
        }

        pendingUsers.remove(email, pending);

        boolean login = false;

        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof jakarta.mail.AuthenticationFailedException) {
                login = true;
            }
        }

        return ResponseEntity.internalServerError().body(
                login
                        ? "Gmail rejected the login. Use a Gmail App Password (not your normal password) "
                          + "in MONETRA_MAIL_PASSWORD and restart Monetra."
                        : "Could not send the OTP email. Check the server console for the reason.");
    }
}

// STEP 2: VERIFY OTP AND CREATE ACCOUNT
@PostMapping("/verify-otp")
public ResponseEntity<String> verifyOtp(
        @RequestBody OtpVerificationRequest request) {

    if (request.email() == null || request.otp() == null) {
        return ResponseEntity.badRequest()
                .body("Email and OTP are required.");
    }

    String email = request.email().trim().toLowerCase();

    PendingUser pending = pendingUsers.get(email);

    if (pending == null) {
        return ResponseEntity.badRequest()
                .body("Request a new OTP first.");
    }

    if (Instant.now().isAfter(pending.expiresAt)) {
        pendingUsers.remove(email, pending);

        return ResponseEntity.badRequest()
                .body("OTP expired. Please request a new one.");
    }

    if (pending.attempts >= 5) {
        pendingUsers.remove(email, pending);

        return ResponseEntity.badRequest()
                .body("Too many attempts. Please request a new OTP.");
    }

    pending.attempts++;

    if (!pending.otp.equals(request.otp().trim())) {
        return ResponseEntity.badRequest()
                .body("Incorrect OTP. Please try again.");
    }

    if (users.existsByEmail(email)) {
        pendingUsers.remove(email, pending);

        return ResponseEntity.badRequest()
                .body("An account already exists for this email.");
    }

    users.save(new UserAccount(email, pending.hashedPassword, pending.phone, pending.fullName));
    pendingUsers.remove(email, pending);

    return ResponseEntity.ok(
            "Email verified and account created successfully!");
}

// STEP 3: LOGIN USING REGISTERED EMAIL AND PASSWORD
@PostMapping("/login")
public ResponseEntity<String> login(
        @RequestBody LoginRequest request,
        HttpServletRequest httpRequest) {

    if (request.email() == null
            || request.password() == null
            || request.email().isBlank()
            || request.password().isEmpty()) {

        return ResponseEntity.badRequest()
                .body("Email and password are required.");
    }

    String email = request.email().trim().toLowerCase();

    UserAccount user = users.findByEmail(email).orElse(null);

    if (user == null || !passwordEncoder.matches(
            request.password(), user.getPasswordHash())) {

        return ResponseEntity.status(401)
                .body("Incorrect email or password.");
    }

    SessionUser.login(httpRequest, user);

    return ResponseEntity.ok("Login successful!");
}

// WHO AM I: lets the page restore the logged-in user after a refresh
@GetMapping("/me")
public ResponseEntity<Map<String, String>> me(HttpSession session) {
    Long id = SessionUser.id(session);

    UserAccount user = id == null ? null : users.findById(id).orElse(null);

    if (user == null) {
        return ResponseEntity.status(401).body(Map.of("error", "Not logged in."));
    }

    return ResponseEntity.ok(Map.of(
            "email", user.getEmail(),
            "name", user.displayName(),
            "phone", user.getPhoneKey() == null ? "" : user.getPhoneKey(),
            "paypalEmail", user.getPaypalEmail() == null ? "" : user.getPaypalEmail()));
}

// LOGOUT: ends the server-side session
@PostMapping("/logout")
public ResponseEntity<String> logout(HttpSession session) {
    SessionUser.logout(session);
    return ResponseEntity.ok("Logged out.");
}

// REQUEST DATA
private record RegistrationRequest(
        String email,
        String password,
        String phone,
        String name) {
}

private record OtpVerificationRequest(
        String email,
        String otp) {
}

private record LoginRequest(
        String email,
        String password) {
}

// TEMPORARY OTP DETAILS
private static class PendingUser {

    private final String hashedPassword;
    private final String phone;
    private final String fullName;
    private final String otp;
    private final Instant expiresAt;
    private final Instant lastSent;
    private int attempts;

    private PendingUser(
            String hashedPassword,
            String phone,
            String fullName,
            String otp,
            Instant expiresAt,
            Instant lastSent,
            int attempts) {

        this.hashedPassword = hashedPassword;
        this.phone = phone;
        this.fullName = fullName;
        this.otp = otp;
        this.expiresAt = expiresAt;
        this.lastSent = lastSent;
        this.attempts = attempts;
    }
}


}

