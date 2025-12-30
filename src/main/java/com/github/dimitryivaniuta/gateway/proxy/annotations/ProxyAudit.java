package com.github.dimitryivaniuta.gateway.proxy.annotations;


import java.lang.annotation.*;

/**
 * Enables audit logging for proxied method invocations.
 *
 * Typical usage:
 * <pre>
 *   @ProxyAudit
 *   public OrderResponse create(...) { ... }
 * </pre>
 *
 * Notes:
 * - The interceptor decides how to persist the audit record (DB, jsonb, etc.).
 * - For sensitive endpoints, disable args/result or set truncation explicitly.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ProxyAudit {

    /**
     * Allows disabling audit on a specific method even if enabled on class.
     */
    boolean enabled() default true;

    /**
     * Capture input arguments to audit storage.
     * Disable for sensitive data or huge payloads.
     */
    boolean captureArgs() default true;

    /**
     * Capture return value to audit storage.
     * Disable for sensitive data or huge payloads.
     */
    boolean captureResult() default true;

    /**
     * Capture exception stack trace when an error occurs.
     * Disable if you want to store only error message/class.
     */
    boolean captureStacktrace() default true;

    /**
     * Overrides max payload truncation for this audited method.
     * -1 means "use global toolkit configuration".
     */
    int maxPayloadChars() default -1;
}
