package com.github.dimitryivaniuta.gateway.proxy.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class ProxyToolkitMetrics {

    private final MeterRegistry registry;

    public ProxyToolkitMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ---- Rate limiting ----
    public void rateLimitRejected(String methodKey, String subjectType) {
        Counter.builder("proxy_toolkit_ratelimit_rejected_total")
                .tag("method", methodKey)
                .tag("subject", subjectType) // apiKey | user | ip | unknown
                .register(registry)
                .increment();
    }

    public void rateLimitAllowed(String methodKey, String subjectType) {
        Counter.builder("proxy_toolkit_ratelimit_allowed_total")
                .tag("method", methodKey)
                .tag("subject", subjectType)
                .register(registry)
                .increment();
    }

    // ---- Retry ----
    public void retryCall(String methodKey) {
        Counter.builder("proxy_toolkit_retry_calls_total")
                .tag("method", methodKey)
                .register(registry)
                .increment();
    }

    public void retryAttempt(String methodKey) {
        Counter.builder("proxy_toolkit_retry_attempts_total")
                .tag("method", methodKey)
                .register(registry)
                .increment();
    }

    public void retryExhausted(String methodKey) {
        Counter.builder("proxy_toolkit_retry_exhausted_total")
                .tag("method", methodKey)
                .register(registry)
                .increment();
    }

    // ---- Cache ----
    public void cacheHit(String cacheName, String methodKey) {
        Counter.builder("proxy_toolkit_cache_hits_total")
                .tag("cache", cacheName)
                .tag("method", methodKey)
                .register(registry)
                .increment();
    }

    public void cacheMiss(String cacheName, String methodKey) {
        Counter.builder("proxy_toolkit_cache_misses_total")
                .tag("cache", cacheName)
                .tag("method", methodKey)
                .register(registry)
                .increment();
    }

    // ---- Idempotency ----
    public void idempotencyServed(String methodKey) {
        Counter.builder("proxy_toolkit_idempotency_served_total")
                .tag("method", methodKey)
                .register(registry)
                .increment();
    }

    public void idempotencyExecuted(String methodKey) {
        Counter.builder("proxy_toolkit_idempotency_executed_total")
                .tag("method", methodKey)
                .register(registry)
                .increment();
    }

    public void idempotencyInFlightConflict(String methodKey) {
        Counter.builder("proxy_toolkit_idempotency_inflight_conflict_total")
                .tag("method", methodKey)
                .register(registry)
                .increment();
    }

    // ---- Duration ----
    public void recordDuration(String metricName, String methodKey, long nanos) {
        Timer.builder(metricName)
                .tag("method", methodKey)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }
}
