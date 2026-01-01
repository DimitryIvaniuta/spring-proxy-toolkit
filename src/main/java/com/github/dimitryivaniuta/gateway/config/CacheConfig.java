package com.github.dimitryivaniuta.gateway.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.dimitryivaniuta.gateway.proxy.cache.TtlCaffeineCacheManager;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Cache configuration for Proxy Toolkit:
 * - Caffeine local cache
 * - TTL per-cache via name convention: "cacheName:ttl=30"
 *   (implemented by TtlCaffeineCacheManager)
 *
 * This is intentionally local (no Redis) in the chosen project setup.
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        // Base builder. Actual TTL can be overridden per cache by ":ttl=NN" suffix.
        return new TtlCaffeineCacheManager(() ->
                Caffeine.newBuilder()
                        .maximumSize(50_000)
                        .expireAfterAccess(Duration.ofMinutes(10)) // base fallback (if :ttl= not provided)
                        .recordStats()
        );
    }
}
