package com.github.dimitryivaniuta.gateway.proxy.ratelimit;

import com.github.dimitryivaniuta.gateway.proxy.client.ApiClientCredentialLookupService;
import com.github.dimitryivaniuta.gateway.proxy.client.ApiKeyHashService;
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

    /**
     * subjectKey format is stable and used in:
     * - rate limit bucket keys
     * - api_client_policy.client_key
     *
     * Examples:
     * - apiKey:<hash>
     * - user:<username>
     * - ip:<address>
     */
    public record ResolvedClient(SubjectType subjectType, String subjectKey, boolean knownApiKey) {}

    private final ApiKeyHashService hashService;
    private final ApiClientCredentialLookupService credentialLookup;

    public RateLimitKeyResolver(ApiKeyHashService hashService,
                                ApiClientCredentialLookupService credentialLookup) {
        this.hashService = hashService;
        this.credentialLookup = credentialLookup;
    }

    public ResolvedClient resolve() {
        HttpServletRequest req = currentRequest().orElse(null);
        if (req == null) return new ResolvedClient(SubjectType.UNKNOWN, "unknown", false);

        // 1) API key (preferred)
        String rawApiKey = header(req, "X-Api-Key");
        if (rawApiKey != null && !rawApiKey.isBlank()) {
            String hash = hashService.hash(rawApiKey);
            boolean known = credentialLookup.findActiveByHash(hash).isPresent();
            return new ResolvedClient(SubjectType.API_KEY, "apiKey:" + hash, known);
        }

        // 2) Authenticated user (if you have security enabled)
        Principal p = req.getUserPrincipal();
        if (p != null && p.getName() != null && !p.getName().isBlank()) {
            return new ResolvedClient(SubjectType.USER, "user:" + p.getName(), false);
        }

        // 3) IP fallback
        String ip = resolveClientIp(req);
        if (ip != null && !ip.isBlank()) {
            return new ResolvedClient(SubjectType.IP, "ip:" + ip, false);
        }

        return new ResolvedClient(SubjectType.UNKNOWN, "unknown", false);
    }

    private static Optional<HttpServletRequest> currentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a)) return Optional.empty();
        return Optional.ofNullable(a.getRequest());
    }

    private static String header(HttpServletRequest req, String name) {
        String v = req.getHeader(name);
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String resolveClientIp(HttpServletRequest req) {
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
