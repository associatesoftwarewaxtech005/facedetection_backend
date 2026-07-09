package com.loginsystem;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP-based sliding-window rate limiter for biometric scan endpoints.
 *
 * Only applies to paths under /api/attendance/ (check-in, check-out, verify-biometrics).
 * Returns HTTP 429 Too Many Requests when an IP exceeds the configured limit.
 */
@Component
@Order(1)
public class RateLimitFilter implements Filter {

    @Value("${biometric.rate-limit-per-minute:10}")
    private int maxRequestsPerMinute;

    private static final long WINDOW_MS = 60_000L; // 1-minute sliding window

    /** Maps IP → list of request timestamps within the current window. */
    private final Map<String, long[]> ipTimestamps = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest  httpReq  = (HttpServletRequest)  request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        String path = httpReq.getRequestURI();

        // Only rate-limit biometric scan endpoints
        if (path.startsWith("/api/attendance/")) {
            String ip = getClientIp(httpReq);
            if (!isAllowed(ip)) {
                httpResp.setStatus(429); // Too Many Requests
                httpResp.setContentType("application/json");
                httpResp.getWriter().write(
                    "{\"message\":\"RATE LIMIT EXCEEDED: Too many scan attempts. Please wait 60 seconds.\"}");
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isAllowed(String ip) {
        long now = Instant.now().toEpochMilli();
        long cutoff = now - WINDOW_MS;

        ipTimestamps.compute(ip, (key, timestamps) -> {
            if (timestamps == null) {
                return new long[]{now};
            }
            // Count requests within the window
            int count = 0;
            for (long t : timestamps) {
                if (t >= cutoff) count++;
            }
            // Build new array with only recent timestamps plus current
            long[] updated = new long[count + 1];
            int idx = 0;
            for (long t : timestamps) {
                if (t >= cutoff) updated[idx++] = t;
            }
            updated[idx] = now;
            return updated;
        });

        long[] current = ipTimestamps.get(ip);
        if (current == null) return true;

        long cutoffCheck = now - WINDOW_MS;
        int countInWindow = 0;
        for (long t : current) {
            if (t >= cutoffCheck) countInWindow++;
        }
        return countInWindow <= maxRequestsPerMinute;
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
