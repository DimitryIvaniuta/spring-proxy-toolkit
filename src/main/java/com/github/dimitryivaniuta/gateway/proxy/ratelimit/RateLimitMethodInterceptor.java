package com.github.dimitryivaniuta.gateway.proxy.ratelimit;

import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyRateLimit;
import com.github.dimitryivaniuta.gateway.proxy.metrics.ProxyToolkitMetrics;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicy;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicyService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public final class RateLimitMethodInterceptor implements MethodInterceptor {

    private final RateLimitKeyResolver keyResolver;
    private final ApiClientPolicyService policyService;
    private final ProxyToolkitMetrics metrics;

    /**
     * Cache limiters by (methodKey + subjectType + effective policy params). DO NOT key by subjectKey
     * to avoid unbounded growth.
     *
     * Primary RL is at API Gateway; backend RL should be coarse and bounded.
     */
    private final ConcurrentHashMap<LimiterKey, RateLimiter> limiterCache = new ConcurrentHashMap<>();

    @Override
    public Object invoke(MethodInvocation inv) throws Throwable {
        ProxyRateLimit cfg = find(inv.getThis().getClass(), inv.getMethod());
        if (cfg == null) return inv.proceed();

        final String methodKey = methodKey(inv);

        final RateLimitKeyResolver.ResolvedClient subject = keyResolver.resolve();
        final String subjectType = subject.subjectType().tag();
        final String subjectKey = safeSubjectKey(subject); // used ONLY for policy lookup

        final ApiClientPolicy policy = (subjectKey == null)
                ? null
                : policyService.find(subjectKey, methodKey).orElse(null);

        // If explicitly disabled for this client+method => skip backend RL (gateway is primary anyway).
        if (policy != null && !policy.isEnabled()) {
            return inv.proceed();
        }

        // Effective params: policy override -> annotation -> clamp
        final int effPps = clamp(policy != null ? policy.getRlPermitsPerSec() : null, cfg.permitsPerSecond(), 1, 100_000);
        final int effBurst = clamp(policy != null ? policy.getRlBurst() : null, cfg.burst(), 0, 100_000);

        // Resilience4j doesn't have a real "burst bucket". We approximate by raising limitForPeriod.
        final int limitForPeriod = (effBurst > 0) ? Math.max(effPps, effBurst) : effPps;
        final Duration refreshPeriod = Duration.ofSeconds(1);
        final Duration timeout = Duration.ZERO; // fail fast for HTTP

        final LimiterKey lk = LimiterKey.of(methodKey, subjectType, limitForPeriod, refreshPeriod, timeout);
        final RateLimiter limiter = limiterCache.computeIfAbsent(
                lk,
                k -> buildLimiter(k.name(), limitForPeriod, refreshPeriod, timeout)
        );

        boolean rejected = false;
        try {
            // decorate checked supplier to keep Throwable contract
            return RateLimiter.decorateCheckedSupplier(limiter, inv::proceed).get();
        } catch (RequestNotPermitted ex) {
            rejected = true;
            long retryAfterSeconds = retryAfterSeconds(refreshPeriod);
            metrics.rateLimitRejected(methodKey, subjectType);
            throw new RateLimitExceededException("Rate limit exceeded", retryAfterSeconds);
        } finally {
            // If not rejected, it's allowed (even if proceed() throws business exception).
            if (!rejected) {
                metrics.rateLimitAllowed(methodKey, subjectType);
            }
        }
    }

    private static ProxyRateLimit find(Class<?> cls, Method m) {
        ProxyRateLimit onMethod = AnnotatedElementUtils.findMergedAnnotation(m, ProxyRateLimit.class);
        return (onMethod != null) ? onMethod : AnnotatedElementUtils.findMergedAnnotation(cls, ProxyRateLimit.class);
    }

    private static String methodKey(MethodInvocation inv) {
        return inv.getThis().getClass().getName() + "#" + inv.getMethod().getName();
    }

    private static RateLimiter buildLimiter(String name, int limitForPeriod, Duration refreshPeriod, Duration timeout) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(refreshPeriod)
                .timeoutDuration(timeout)
                .build();
        return RateLimiter.of(name, config);
    }

    private static long retryAfterSeconds(Duration refreshPeriod) {
        long s = refreshPeriod.toSeconds();
        return (s <= 0) ? 1L : s;
    }

    private static int clamp(Integer override, int fallback, int min, int max) {
        int v = (override != null) ? override : fallback;
        if (v < min) return min;
        return Math.min(v, max);
    }

    /**
     * Your ResolvedClient likely has a "subjectKey" (apiKey:..., user:..., ip:...).
     * If your method name differs (e.g. key(), clientKey()), update ONLY this method.
     */
    private static String safeSubjectKey(RateLimitKeyResolver.ResolvedClient subject) {
        try {
            // expected: subject.subjectKey()
            return (String) subject.getClass().getMethod("subjectKey").invoke(subject);
        } catch (Exception ignored) {
            return null; // no subjectKey available -> no policy lookup
        }
    }

    private record LimiterKey(
            String methodKey,
            String subjectType,
            int limitForPeriod,
            Duration refreshPeriod,
            Duration timeout
    ) {
        static LimiterKey of(String methodKey, String subjectType, int limitForPeriod, Duration refreshPeriod, Duration timeout) {
            return new LimiterKey(methodKey, subjectType, limitForPeriod, refreshPeriod, timeout);
        }

        String name() {
            // stable, low-cardinality name for registry
            return "rl:" + methodKey + ":" + subjectType + ":" + limitForPeriod;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LimiterKey that)) return false;
            return limitForPeriod == that.limitForPeriod
                    && Objects.equals(methodKey, that.methodKey)
                    && Objects.equals(subjectType, that.subjectType)
                    && Objects.equals(refreshPeriod, that.refreshPeriod)
                    && Objects.equals(timeout, that.timeout);
        }

        @Override
        public int hashCode() {
            return Objects.hash(methodKey, subjectType, limitForPeriod, refreshPeriod, timeout);
        }
    }
}
