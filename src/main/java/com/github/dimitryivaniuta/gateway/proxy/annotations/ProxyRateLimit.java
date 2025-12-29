package com.github.dimitryivaniuta.gateway.proxy.annotations;

import java.lang.annotation.*;

/**
 * Enables defense-in-depth rate limiting at the service/method level (backend-side).
 *
 * <p>IMPORTANT:
 * <ul>
 *   <li>Primary rate limiting should be enforced at the API Gateway.</li>
 *   <li>This annotation is intended for additional protection of expensive endpoints/methods.</li>
 *   <li>This project uses Resilience4j RateLimiter under the hood (fixed refresh period).</li>
 * </ul>
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code permitsPerSecond} defines the allowed operations per second (minimum 1).</li>
 *   <li>{@code burst} is treated as an approximation by increasing the per-second limit (Resilience4j is not a token bucket).</li>
 *   <li>{@code key} is optional: for future extensibility (not used by the current resolver-based keying).</li>
 * </ul>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ProxyRateLimit {

    /**
     * Allowed operations per second for this method/class.
     */
    int permitsPerSecond();

    /**
     * Optional "burst" approximation. If > 0, backend limiter uses max(permitsPerSecond, burst) as limitForPeriod.
     */
    int burst() default 0;

    /**
     * Optional static key override (reserved for future use).
     */
    String key() default "";

}
