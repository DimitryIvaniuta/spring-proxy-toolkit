package com.github.dimitryivaniuta.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Extracts idempotency key header and stores it in MDC for IdempotencyMethodInterceptor.
 * Extracts X-Idempotency-Key from request and stores it in MDC for downstream usage:
 * - IdempotencyMethodInterceptor reads it from MDC to enforce idempotency.
 *
 * Notes:
 * - Does NOT enforce presence/format; enforcement is done by @ProxyIdempotent(requireKey=true).
 * - Keeps header name stable for clients.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15) // after CorrelationIdFilter, before controllers
public class IdempotencyKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Idempotency-Key";
    public static final String ALT_HEADER = "Idempotency-Key"; // optional compatibility
    public static final String MDC_KEY = "idempotencyKey";

    private static final int MAX_LEN = 128;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return p != null && (p.startsWith("/actuator") || p.startsWith("/swagger") || p.startsWith("/v3/api-docs"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String key = firstHeader(request, HEADER, ALT_HEADER);

        if (key != null) {
            key = key.trim();
            // no whitespace-only keys
            if (!key.isEmpty()) {
                if (key.length() > MAX_LEN) {
                    key = key.substring(0, MAX_LEN);
                }
                MDC.put(MDC_KEY, key);
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String firstHeader(HttpServletRequest req, String... names) {
        for (String n : names) {
            String v = req.getHeader(n);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
