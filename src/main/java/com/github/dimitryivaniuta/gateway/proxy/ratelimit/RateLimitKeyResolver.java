package com.github.dimitryivaniuta.gateway.proxy.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.Principal;
import java.util.Optional;

@Component
public final class RateLimitKeyResolver {

    public enum SubjectType {
        API_KEY("apiKey"),
        USER("user"),
        IP("ip"),
        UNKNOWN("unknown");

        private final String tag;
        SubjectType(String tag) { this.tag = tag; }
        public String tag() { return tag; }
    }

    public record Subject(String key, SubjectType type) {}

    public Subject resolve() {
        HttpServletRequest req = currentRequest().orElse(null);
        if (req == null) return new Subject("unknown", SubjectType.UNKNOWN);

        // 1) API key (best for public APIs)
        String apiKey = header(req, "X-Api-Key");
        if (apiKey != null) return new Subject("apiKey:" + apiKey, SubjectType.API_KEY);

        // 2) Explicit user header (if your gateway/auth layer provides it)
        String userId = firstNonBlank(header(req, "X-User-Id"), header(req, "X-User"));
        if (userId != null) return new Subject("user:" + userId, SubjectType.USER);

        // 3) Servlet container principal (works with basic/container auth; no Spring Security required)
        Principal p = req.getUserPrincipal();
        if (p != null && p.getName() != null && !p.getName().isBlank()) {
            return new Subject("user:" + p.getName(), SubjectType.USER);
        }

        // 4) Client IP (supports reverse proxies)
        String ip = clientIp(req);
        if (ip != null) return new Subject("ip:" + ip, SubjectType.IP);

        return new Subject("unknown", SubjectType.UNKNOWN);
    }

    public String resolveKey() {
        return resolve().key();
    }

    public String resolveSubjectTypeTag() {
        return resolve().type().tag();
    }

    private static Optional<HttpServletRequest> currentRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) return Optional.ofNullable(sra.getRequest());
        return Optional.empty();
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private static String clientIp(HttpServletRequest req) {
        // X-Forwarded-For may contain "client, proxy1, proxy2"
        String xff = header(req, "X-Forwarded-For");
        if (xff != null) {
            int comma = xff.indexOf(',');
            String first = (comma >= 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isBlank()) return first;
        }
        String realIp = header(req, "X-Real-IP");
        if (realIp != null) return realIp;

        String ra = req.getRemoteAddr();
        return (ra == null || ra.isBlank()) ? null : ra;
    }
}
