package com.monetra;

import jakarta.servlet.http.HttpSession;

/**
 * The logged-in user, kept in the server-side session (JSESSIONID cookie).
 * Private endpoints take the user from here, never from the request body.
 */
public final class SessionUser {

    private static final String ID_KEY = "monetra.userId";
    private static final String EMAIL_KEY = "monetra.userEmail";

    private SessionUser() {
    }

    /** Logs the user in. The old session id is replaced to prevent session fixation. */
    public static void login(jakarta.servlet.http.HttpServletRequest request, UserAccount user) {
        HttpSession old = request.getSession(false);

        if (old != null) {
            old.invalidate();
        }

        HttpSession session = request.getSession(true);
        session.setAttribute(ID_KEY, user.getId());
        session.setAttribute(EMAIL_KEY, user.getEmail());
    }

    public static void logout(HttpSession session) {
        if (session != null) {
            session.invalidate();
        }
    }

    /** Returns the logged-in user's id, or null when nobody is logged in. */
    public static Long id(HttpSession session) {
        Object value = session == null ? null : session.getAttribute(ID_KEY);
        return value instanceof Long ? (Long) value : null;
    }

    public static String email(HttpSession session) {
        Object value = session == null ? null : session.getAttribute(EMAIL_KEY);
        return value instanceof String ? (String) value : null;
    }
}
