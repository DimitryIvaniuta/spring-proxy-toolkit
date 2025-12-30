package com.github.dimitryivaniuta.gateway.proxy.annotations;

import java.lang.annotation.*;

/**
 * Enables idempotency for a method via the toolkit proxy interceptor.
 *
 * Typical usage: writes/commands that can be retried by clients safely.
 *
 * The interceptor uses an idempotency key, usually taken from HTTP header
 * (e.g., X-Idempotency-Key) or other request context (MDC).
 *
 * Policy overrides may disable idempotency or override TTL per client+method.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ProxyIdempotent {

    /**
     * Allows disabling idempotency on a method even if enabled on class.
     */
    boolean enabled() default true;

    /**
     * Default TTL for idempotency records in seconds.
     * Policy override may replace this value.
     */
    long ttlSeconds() default 24 * 60 * 60; // 24h

    /**
     * If true, missing idempotency key should be treated as a client error (400).
     * If false, method executes normally when key is absent.
     */
    boolean requireKey() default false;

    /**
     * If true, reuse of same idempotency key with different request payload must be rejected (409).
     */
    boolean conflictOnDifferentRequest() default true;

    /**
     * If true, concurrent in-flight requests with the same key are not allowed
     * (interceptor may wait/poll briefly or return conflict).
     */
    boolean rejectInFlight() default true;
}
