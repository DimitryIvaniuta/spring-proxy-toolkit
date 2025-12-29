package com.github.dimitryivaniuta.gateway.proxy.metrics;

import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyRetry;
import com.github.dimitryivaniuta.gateway.proxy.metrics.ProxyToolkitMetrics;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public final class RetryMethodInterceptor implements MethodInterceptor {

    private final ProxyToolkitMetrics metrics;

    private final ConcurrentHashMap<RetryKey, Retry> retryCache = new ConcurrentHashMap<>();

    @Override
    public Object invoke(MethodInvocation inv) throws Throwable {
        ProxyRetry cfg = find(inv.getThis().getClass(), inv.getMethod());
        if (cfg == null) return inv.proceed();

        final String methodKey = inv.getThis().getClass().getName() + "#" + inv.getMethod().getName();
        final RetryKey key = RetryKey.of(methodKey, cfg);

        Retry retry = retryCache.computeIfAbsent(key, k -> buildRetry(k.name(), cfg));

        metrics.retryCall(methodKey);
        long start = System.nanoTime();

        try {
            return Retry.decorateCheckedSupplier(retry, () -> {
                metrics.retryAttempt(methodKey);
                return inv.proceed();
            }).get();
        } catch (Throwable ex) {
            metrics.retryExhausted(methodKey);
            throw ex;
        } finally {
            metrics.recordDuration("proxy_toolkit_retry_duration_seconds", methodKey, System.nanoTime() - start);
        }
    }

    private static ProxyRetry find(Class<?> cls, Method m) {
        ProxyRetry onMethod = AnnotatedElementUtils.findMergedAnnotation(m, ProxyRetry.class);
        return (onMethod != null) ? onMethod : AnnotatedElementUtils.findMergedAnnotation(cls, ProxyRetry.class);
    }

    private static Retry buildRetry(String name, ProxyRetry cfg) {
        int maxAttempts = Math.max(1, cfg.maxAttempts());
        long baseBackoffMs = Math.max(0, cfg.backoffMs());

        IntervalFunction backoff = IntervalFunction
                .ofExponentialBackoff(Duration.ofMillis(baseBackoffMs), 2.0)
                .withJitter(0.2);

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(backoff)
                .retryOnException(ex -> shouldRetry(ex, cfg.retryOn()))
                .failAfterMaxAttempts(true)
                .build();

        return Retry.of(name, config);
    }

    private static boolean shouldRetry(Throwable ex, Class<? extends Throwable>[] retryOn) {
        Throwable t = rootCause(ex);
        for (Class<? extends Throwable> c : retryOn) {
            if (c.isInstance(t)) return true;
        }
        return false;
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur;
    }

    private record RetryKey(String methodKey, int maxAttempts, long backoffMs, String retryOnSig) {
        static RetryKey of(String methodKey, ProxyRetry cfg) {
            String sig = Arrays.stream(cfg.retryOn())
                    .filter(Objects::nonNull)
                    .map(Class::getName)
                    .sorted()
                    .reduce((a, b) -> a + "|" + b)
                    .orElse("");
            return new RetryKey(methodKey, cfg.maxAttempts(), cfg.backoffMs(), sig);
        }

        String name() {
            return "retry:" + methodKey + ":" + maxAttempts + ":" + backoffMs + ":" + retryOnSig.hashCode();
        }
    }
}
