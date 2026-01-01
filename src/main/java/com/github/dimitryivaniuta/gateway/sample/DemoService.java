package com.github.dimitryivaniuta.gateway.sample;

import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyAudit;
import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyCache;
import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyIdempotent;
import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyRateLimit;
import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyRetry;
import com.github.dimitryivaniuta.gateway.proxy.ratelimit.RateLimitKeyResolver;
import com.github.dimitryivaniuta.gateway.sample.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class DemoService {

    private final RateLimitKeyResolver keyResolver;

    /**
     * Cache demo: returns a stable UUID per customerId (should remain same on repeated calls).
     */
    @ProxyAudit(captureArgs = true, captureResult = true)
    @ProxyCache(cacheName = "demoCustomerCache", ttlSeconds = 60)
    public DemoCacheResponse cachedCustomerView(Long customerId) {
        return new DemoCacheResponse(
                customerId,
                UUID.randomUUID().toString(),
                Instant.now()
        );
    }

    /**
     * Idempotency demo: requires X-Idempotency-Key. Repeating same key returns same response.
     */
    @ProxyAudit(captureArgs = true, captureResult = true)
    @ProxyIdempotent(requireKey = true, ttlSeconds = 24 * 60 * 60, conflictOnDifferentRequest = true, rejectInFlight = true)
    public DemoIdempotentResponse idempotentPayment(DemoIdempotentRequest req) {
        return new DemoIdempotentResponse(
                UUID.randomUUID().toString(),
                req.amount(),
                req.currency(),
                "ACCEPTED",
                Instant.now()
        );
    }

    /**
     * Rate limit demo: call several times quickly to trigger 429.
     * Primary RL belongs in API Gateway; this is defense-in-depth.
     */
    @ProxyAudit(captureArgs = false, captureResult = true)
    @ProxyRateLimit(permitsPerSecond = 2, burst = 2)
    public DemoRateLimitedResponse rateLimitedPing() {
        return new DemoRateLimitedResponse("OK", Instant.now());
    }

    /**
     * Retry demo: fails first N times per subject+failTimes and then succeeds.
     * Use Postman: /api/demo/retry?failTimes=2
     */
    private final ConcurrentHashMap<String, AtomicInteger> retryCounters = new ConcurrentHashMap<>();

    @ProxyAudit(captureArgs = true, captureResult = true)
    @ProxyRetry(maxAttempts = 4, backoffMs = 200, retryOn = {DemoTransientException.class})
    public DemoRetryResponse retryDemo(int failTimes) {
        String subjectKey = keyResolver.resolve().subjectKey();
        String counterKey = subjectKey + "|failTimes=" + failTimes;

        AtomicInteger n = retryCounters.computeIfAbsent(counterKey, k -> new AtomicInteger(0));
        int attempt = n.incrementAndGet();

        if (attempt <= failTimes) {
            throw new DemoTransientException("Simulated transient failure attempt=" + attempt + "/" + failTimes);
        }

        // cleanup so the demo can be repeated
        retryCounters.remove(counterKey);

        return new DemoRetryResponse(
                "SUCCESS",
                attempt,
                failTimes,
                subjectKey,
                Instant.now()
        );
    }
}
