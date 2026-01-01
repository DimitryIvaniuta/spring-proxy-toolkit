package com.github.dimitryivaniuta.gateway.proxy.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.AbstractCacheManager;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Caffeine CacheManager that supports per-cache TTL via cache name convention:
 *
 *   "ordersByCustomer:ttl=30"   -> expireAfterWrite 30 seconds
 *   "apiKeyLookup:ttl=60"       -> expireAfterWrite 60 seconds
 *   "apiClientPolicy:ttl=30"    -> expireAfterWrite 30 seconds
 * If ":ttl=" is not present or invalid, it falls back to the base builder's configuration.
 *
 * Notes:
 * - This manager is intentionally local/in-memory (no Redis) for this project setup.
 * - We cache missing caches in a ConcurrentHashMap and return stable Cache instances.
 * - TTL is parsed and clamped to avoid accidental huge values.
 * IMPORTANT:
 * Caffeine builders are mutable. You MUST create a fresh builder per cache.
 * This manager therefore uses a Supplier<Caffeine<..>> factory.
 */
public final class TtlCaffeineCacheManager extends AbstractCacheManager {

    private static final Pattern TTL_PATTERN = Pattern.compile("^(?<base>.+?)(?::ttl=(?<ttl>\\d+))?$");
    private static final long MIN_TTL_SECONDS = 1;
    private static final long MAX_TTL_SECONDS = 24 * 60 * 60; // 24h safety cap

    private final Supplier<Caffeine<Object, Object>> baseBuilderFactory;
    private final Map<String, Cache> cacheMap = new ConcurrentHashMap<>();

    public TtlCaffeineCacheManager(Supplier<Caffeine<Object, Object>> baseBuilderFactory) {
        this.baseBuilderFactory = Objects.requireNonNull(baseBuilderFactory, "baseBuilderFactory must not be null");
    }

    @Override
    protected Collection<? extends Cache> loadCaches() {
        // no predefined caches; created lazily in getMissingCache(...)
        return List.of();
    }

    @Override
    protected Cache getMissingCache(String name) {
        return cacheMap.computeIfAbsent(name, this::createCache);
    }

    private Cache createCache(String name) {
        Parsed parsed = parse(name);

        // CRITICAL: fresh builder per cache (prevents "expireAfterWrite already set")
        Caffeine<Object, Object> builder = baseBuilderFactory.get();

        if (parsed.ttlSeconds != null) {
            builder = builder.expireAfterWrite(Duration.ofSeconds(parsed.ttlSeconds));
        }

        // Use full name (incl ":ttl=") so different TTLs become different caches.
        return new CaffeineCache(name, builder.build());
    }

    private static Parsed parse(String name) {
        if (name == null || name.isBlank()) {
            return new Parsed(name, null);
        }

        Matcher m = TTL_PATTERN.matcher(name.trim());
        if (!m.matches()) {
            return new Parsed(name, null);
        }

        String ttlGroup = m.group("ttl");
        if (ttlGroup == null) {
            return new Parsed(name, null);
        }

        try {
            long ttl = Long.parseLong(ttlGroup);
            ttl = clamp(ttl, MIN_TTL_SECONDS, MAX_TTL_SECONDS);
            return new Parsed(name, ttl);
        } catch (NumberFormatException ex) {
            return new Parsed(name, null);
        }
    }

    private static long clamp(long v, long min, long max) {
        if (v < min) return min;
        return Math.min(v, max);
    }

    private record Parsed(String cacheName, Long ttlSeconds) {}
}
