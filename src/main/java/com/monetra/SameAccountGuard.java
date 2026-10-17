package com.monetra;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Keeps a page that was left open for one person from acting as another.
 *
 * The browser shares ONE login cookie between all of its tabs. If someone signs in as a different person in
 * another tab, a tab that is still showing the first person would quietly send its requests (and its saved
 * records) as the second person. So the page says who it believes is signed in (the X-Monetra-User header)
 * and a request that does not match the session is refused with 409, before anything is read or written.
 *
 * A request without the header (the tests, Postman, scripts) is not checked, and so is a request with no
 * session: the endpoint itself answers "log in first" as before.
 */
@Component
public class SameAccountGuard implements HandlerInterceptor {

    public static final String HEADER = "X-Monetra-User";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String claimed = request.getHeader(HEADER);

        if (claimed == null || claimed.isBlank()) {
            return true;
        }

        HttpSession session = request.getSession(false);
        String signedIn = SessionUser.email(session);

        if (signedIn == null || signedIn.equalsIgnoreCase(claimed.trim())) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"You signed in as a different person in another tab. "
                + "This page will reload.\",\"accountChanged\":true}");

        return false;
    }
}
