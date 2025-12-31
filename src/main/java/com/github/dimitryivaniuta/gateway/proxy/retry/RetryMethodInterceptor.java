package com.github.dimitryivaniuta.gateway.proxy.retry;

import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyRetry;
import com.github.dimitryivaniuta.gateway.proxy.metrics.ProxyToolkitMetrics;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicy;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicyService;
import com.github.dimitryivaniuta.gateway.proxy.ratelimit.RateLimitKeyResolver;
import com.github.dimitryivaniuta.gateway.proxy.support.MethodKeySupport;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public class RetryMethodInterceptor implements MethodInterceptor {

    private final ConcurrentHashMap<RetryKey, Retry> retryCache = new ConcurrentHashMap<>();

    private final RateLimitKeyResolver keyResolver;
    private final ApiClientPolicyService policyService;
    private final ProxyToolkitMetrics metrics;

    public RetryMethodInterceptor(RateLimitKeyResolver keyResolver,
                                  ApiClientPolicyService policyService,
                                  ProxyToolkitMetrics metrics) {
        this.keyResolver = keyResolver;
        this.policyService = policyService;
        this.metrics = metrics;
    }

    @Override
    public Object invoke(MethodInvocation inv) throws Throwable {
        ProxyRetry ann = find(inv.getThis().getClass(), inv.getMethod());
        if (ann == null) return inv.proceed();

        var client = keyResolver.resolve();
        String fullMethodKey = MethodKeySupport.signature(inv.getThis().getClass(), inv.getMethod());
        String metricMethodKey = MethodKeySupport.metricMethodKey(inv.getThis().getClass(), inv.getMethod());

        ApiClientPolicy policy = policyService.find(client.subjectKey(), fullMethodKey).orElse(null);
        if (policy != null && !policy.isEnabled()) {
            return inv.proceed();
        }

        int maxAttempts = (policy != null && policy.getRetryMaxAttempts() != null)
                ? MethodKeySupport.clampInt(policy.getRetryMaxAttempts(), ann.maxAttempts(), 1, 20)
                : ann.maxAttempts();

        int backoffMs = (policy != null && policy.getRetryBackoffMs() != null)
                ? MethodKeySupport.clampInt(policy.getRetryBackoffMs(), (int) ann.backoffMs(), 0, 60_000)
                : (int) ann.backoffMs();

        RetryKey key = RetryKey.of(fullMethodKey, maxAttempts, backoffMs, ann.retryOn());

        Retry retry = retryCache.computeIfAbsent(key, k -> buildRetry(k, ann, maxAttempts, backoffMs));

        metrics.retryCall(metricMethodKey);
        final int[] attempt = {0};
        long start = System.nanoTime();

        try {
            return Retry.decorateCheckedSupplier(retry, () -> {
                attempt[0]++;
                metrics.retryAttempt(metricMethodKey);
                return inv.proceed();
            }).get();
        } catch (Throwable ex) {
            metrics.retryExhausted(metricMethodKey);
            throw ex;
        } finally {
            metrics.recordDuration("proxy_toolkit_retry_duration_seconds", metricMethodKey, System.nanoTime() - start);
        }
    }

    private Retry buildRetry(RetryKey key, ProxyRetry ann, int maxAttempts, int backoffMs) {
        Predicate<Throwable> retryOn = ex ->
                Arrays.stream(ann.retryOn()).anyMatch(c -> c.isInstance(ex));

        IntervalFunction interval = (backoffMs <= 0)
                ? IntervalFunction.ofDefaults()
                : IntervalFunction.ofExponentialBackoff(Duration.ofMillis(backoffMs), 2.0).withJitter(0.2);

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .intervalFunction(interval)
                .retryOnException(retryOn::test)
                .failAfterMaxAttempts(true)
                .build();

        return Retry.of("retry:" + key.methodKey, config);
    }

    private static ProxyRetry find(Class<?> cls, Method m) {
        ProxyRetry onMethod = AnnotatedElementUtils.findMergedAnnotation(m, ProxyRetry.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(cls, ProxyRetry.class);
    }

    private static final class RetryKey {
        final String methodKey;
        final int maxAttempts;
        final int backoffMs;
        final String retryOnSig;

        private RetryKey(String methodKey, int maxAttempts, int backoffMs, String retryOnSig) {
            this.methodKey = methodKey;
            this.maxAttempts = maxAttempts;
            this.backoffMs = backoffMs;
            this.retryOnSig = retryOnSig;
        }

        static RetryKey of(String methodKey, int maxAttempts, int backoffMs, Class<? extends Throwable>[] retryOn) {
            String sig = Arrays.stream(retryOn).map(Class::getName).sorted().reduce((a,b)->a+"|"+b).orElse("");
            return new RetryKey(methodKey, maxAttempts, backoffMs, sig);
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RetryKey k)) return false;
            return maxAttempts == k.maxAttempts && backoffMs == k.backoffMs
                    && Objects.equals(methodKey, k.methodKey)
                    && Objects.equals(retryOnSig, k.retryOnSig);
        }
        @Override public int hashCode() { return Objects.hash(methodKey, maxAttempts, backoffMs, retryOnSig); }
    }
}
