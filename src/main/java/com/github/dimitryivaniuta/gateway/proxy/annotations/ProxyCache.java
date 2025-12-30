package com.github.dimitryivaniuta.gateway.proxy.annotations;

import java.lang.annotation.*;

/**
 * Enables caching for a method via the toolkit proxy interceptor.
 *
 * - Intended for read/pure methods.
 * - Policy overrides may disable caching or override TTL per client+method.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ProxyCache {

    /**
     * Logical cache name. Toolkit may internally derive a physical name
     * (e.g., add TTL suffix "name:ttl=30").
     */
    String cacheName();

    /**
     * Default TTL in seconds.
     * Policy override may replace this value.
     */
    long ttlSeconds() default 60;

    /**
     * Cache scope to avoid data leakage across clients/users.
     * SUBJECT means cache key is automatically scoped by resolved clientKey.
     */
    CacheScope scope() default CacheScope.SUBJECT;

    /**
     * Allows disabling caching on a method even if enabled on class.
     */
    boolean enabled() default true;

    enum CacheScope {
        /**
         * Shared cache across all callers (dangerous unless output is truly global).
         */
        GLOBAL,
        /**
         * Cache partitioned per resolved subject (apiKey/user/ip).
         */
        SUBJECT
    }
}
