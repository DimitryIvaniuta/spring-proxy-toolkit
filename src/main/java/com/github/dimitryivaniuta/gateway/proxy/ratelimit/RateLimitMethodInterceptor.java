package com.github.dimitryivaniuta.gateway.proxy.ratelimit;

import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyRateLimit;
import com.github.dimitryivaniuta.gateway.proxy.metrics.ProxyToolkitMetrics;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicy;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicyService;
import com.github.dimitryivaniuta.gateway.proxy.support.MethodKeySupport;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
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
     * Cache limiters by (method + subjectType + effective params). DO NOT key by subjectKey to avoid unbounded growth.
     */
    private final ConcurrentHashMap<LimiterKey, RateLimiter> limiterCache = new ConcurrentHashMap<>();

    @Override
    public Object invoke(MethodInvocation inv) throws Throwable {
        ProxyRateLimit cfg = find(inv.getThis().getClass(), inv.getMethod());
        if (cfg == null) return inv.proceed();

        Class<?> targetClass = resolveTargetClass(inv);
        String fullMethodKey = MethodKeySupport.signature(targetClass, inv.getMethod());
        String metricMethodKey = MethodKeySupport.metricMethodKey(targetClass, inv.getMethod());

        RateLimitKeyResolver.ResolvedClient subject = keyResolver.resolve();
        String subjectType = subject.subjectType().tag();

        ApiClientPolicy policy = policyService.find(subject.subjectKey(), fullMethodKey).orElse(null);
        if (policy != null && !policy.isEnabled()) {
            return inv.proceed();
        }

        int pps = (policy != null && policy.getRlPermitsPerSec() != null)
                ? MethodKeySupport.clampInt(policy.getRlPermitsPerSec(), cfg.permitsPerSecond(), 1, 100_000)
                : Math.max(1, cfg.permitsPerSecond());

        int burst = (policy != null && policy.getRlBurst() != null)
                ? MethodKeySupport.clampInt(policy.getRlBurst(), cfg.burst(), 0, 100_000)
                : Math.max(0, cfg.burst());

        // Resilience4j doesn't have true burst buckets; approximate by raising limitForPeriod.
        int limitForPeriod = (burst > 0) ? Math.max(pps, burst) : pps;
        Duration refreshPeriod = Duration.ofSeconds(1);
        Duration timeout = Duration.ZERO; // fail fast

        LimiterKey lk = LimiterKey.of(metricMethodKey, subjectType, limitForPeriod, refreshPeriod, timeout);
        RateLimiter limiter = limiterCache.computeIfAbsent(
                lk,
                k -> buildLimiter(k.name(), limitForPeriod, refreshPeriod, timeout)
        );

        boolean rejected = false;
        try {
            // decorate checked supplier to keep Throwable contract
            return RateLimiter.decorateCheckedSupplier(limiter, inv::proceed).get();
        } catch (RequestNotPermitted ex) {
            rejected = true;
            metrics.rateLimitRejected(metricMethodKey, subjectType);
            throw new RateLimitExceededException("Rate limit exceeded", retryAfterSeconds(refreshPeriod));
        } finally {
            if (!rejected) {
                metrics.rateLimitAllowed(metricMethodKey, subjectType);
            }
        }
    }

    private static Class<?> resolveTargetClass(MethodInvocation inv) {
        Object t = inv.getThis();
        Class<?> c = (t != null) ? AopUtils.getTargetClass(t) : null;
        return (c != null) ? c : inv.getMethod().getDeclaringClass();
    }

    private static ProxyRateLimit find(Class<?> cls, Method m) {
        ProxyRateLimit onMethod = AnnotatedElementUtils.findMergedAnnotation(m, ProxyRateLimit.class);
        return (onMethod != null) ? onMethod : AnnotatedElementUtils.findMergedAnnotation(cls, ProxyRateLimit.class);
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
            if (this == o) {
                return true;
            }
            if (!(o instanceof LimiterKey(
                    String key, String type, int forPeriod, Duration period, Duration timeout1
            ))) {
                return false;
            }
            return limitForPeriod == forPeriod
                    && Objects.equals(methodKey, key)
                    && Objects.equals(subjectType, type)
                    && Objects.equals(refreshPeriod, period)
                    && Objects.equals(timeout, timeout1);
        }

        @Override
        public int hashCode() {
            return Objects.hash(methodKey, subjectType, limitForPeriod, refreshPeriod, timeout);
        }
    }
}
