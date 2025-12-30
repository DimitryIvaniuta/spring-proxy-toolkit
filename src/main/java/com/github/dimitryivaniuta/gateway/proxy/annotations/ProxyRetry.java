package com.github.dimitryivaniuta.gateway.proxy.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables retry around a method via the toolkit proxy interceptor.
 *
 * Recommendations:
 * - Apply ONLY to operations that are safe to retry (idempotent calls),
 *   or combine with @ProxyIdempotent.
 * - Keep maxAttempts low for request/response APIs.
 *
 * Policy overrides may override maxAttempts/backoff per client+method.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ProxyRetry {

    /**
     * Allows disabling retry on a method even if enabled on class.
     */
    boolean enabled() default true;

    /**
     * Total attempts including the initial call.
     */
    int maxAttempts() default 3;

    /**
     * Base backoff in milliseconds (interceptor may apply exponential backoff + jitter).
     */
    long backoffMs() default 200;

    /**
     * Only these exception types are retryable.
     * Keep this strict; do not retry on validation / auth / 4xx exceptions.
     */
    Class<? extends Throwable>[] retryOn() default { RuntimeException.class };

    /**
     * Explicit deny-list; if matched, never retry even if retryOn matches.
     */
    Class<? extends Throwable>[] ignoreOn() default {};
}
