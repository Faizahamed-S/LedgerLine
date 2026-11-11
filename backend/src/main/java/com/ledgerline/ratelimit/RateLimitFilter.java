package com.ledgerline.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.common.ApiError;
import com.ledgerline.common.Emails;
import com.ledgerline.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Applies the rate limits. Runs inside the Spring Security chain, right after bearer-token authentication, so the
 * authenticated user is known. Deliberately not a Spring bean: Boot would also register a bean filter as a plain
 * servlet filter, every request would be counted twice, and every limit would effectively be halved.
 * <p>
 * The client IP is {@code request.getRemoteAddr()}; with {@code server.forward-headers-strategy=native} Tomcat sets
 * it from X-Forwarded-For only when the request comes from a trusted internal proxy.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REGISTER_PATH = "/api/auth/register";
    private static final String TRANSFERS_PATH = "/api/transfers";
    private static final Pattern DEPOSIT_OR_WITHDRAWAL = Pattern.compile("^/api/accounts/[^/]+/(deposits|withdrawals)$");

    private final RedisRateLimiter limiter;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RedisRateLimiter limiter, RateLimitProperties properties, ObjectMapper objectMapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    private record Check(String rule, String subject, RateLimitProperties.Rule limit) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.enabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return path.startsWith("/actuator/")
                || path.equals("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean isPost = "POST".equalsIgnoreCase(request.getMethod());
        HttpServletRequest forwarded = request;
        List<Check> checks = new ArrayList<>();

        if (isPost && LOGIN_PATH.equals(path)) {
            CachedBodyHttpServletRequest cached = new CachedBodyHttpServletRequest(request);
            forwarded = cached;
            checks.add(new Check("login-ip", request.getRemoteAddr(), properties.loginIp()));
            String email = emailFrom(cached.body());
            if (email != null && !email.isEmpty()) {
                checks.add(new Check("login-email", email, properties.loginEmail()));
            }
        } else if (isPost && REGISTER_PATH.equals(path)) {
            checks.add(new Check("register-ip", request.getRemoteAddr(), properties.registerIp()));
        } else if (path.startsWith("/api/")) {
            CurrentUser user = currentUser();
            if (user != null) {
                String userId = String.valueOf(user.id());
                checks.add(isPost && isMoneyPath(path)
                        ? new Check("money", userId, properties.money())
                        : new Check("api", userId, properties.api()));
            }
        }

        for (Check check : checks) {
            RedisRateLimiter.Decision decision = limiter.hit(check.rule(), check.subject(), check.limit());
            if (!decision.allowed()) {
                writeTooManyRequests(response, decision.retryAfterSeconds());
                return;
            }
        }
        chain.doFilter(forwarded, response);
    }

    private static boolean isMoneyPath(String path) {
        return TRANSFERS_PATH.equals(path) || DEPOSIT_OR_WITHDRAWAL.matcher(path).matches();
    }

    private String emailFrom(byte[] body) {
        try {
            JsonNode email = objectMapper.readTree(body).get("email");
            return email == null || !email.isTextual() ? null : Emails.normalize(email.asText());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
            return user;
        }
        return null;
    }

    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests, try again in " + retryAfterSeconds + " seconds"));
    }
}
