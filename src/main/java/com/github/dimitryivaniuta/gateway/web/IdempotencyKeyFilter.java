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
    public static final String MDC_KEY = "idempotencyKey";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String key = header(request, HEADER);

        // Optional: basic sanitation (no whitespace-only keys)
        if (key != null && !key.isBlank()) {
            MDC.put(MDC_KEY, key);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }
}
