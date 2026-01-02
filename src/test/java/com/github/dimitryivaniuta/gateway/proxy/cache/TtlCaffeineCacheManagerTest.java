package com.github.dimitryivaniuta.gateway.proxy.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TtlCaffeineCacheManagerTest {

    @Test
    void shouldCreateIndependentCachesForDifferentTtlNames() {
        CacheManager cm = new TtlCaffeineCacheManager(() ->
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterAccess(Duration.ofMinutes(5))
        );

        Cache c60 = cm.getCache("demo:ttl=60");
        Cache c30 = cm.getCache("demo:ttl=30");

        assertThat(c60).isNotNull();
        assertThat(c30).isNotNull();
        assertThat(c60.getName()).isEqualTo("demo:ttl=60");
        assertThat(c30.getName()).isEqualTo("demo:ttl=30");

        c60.put("k", "v1");
        assertThat(c60.get("k", String.class)).isEqualTo("v1");
        assertThat(c30.get("k")).isNull(); // different cache instance
    }

    @Test
    void shouldReturnSameCacheInstanceForSameName() {
        CacheManager cm = new TtlCaffeineCacheManager(() ->
                Caffeine.newBuilder().maximumSize(10_000)
        );

        Cache c1 = cm.getCache("x:ttl=10");
        Cache c2 = cm.getCache("x:ttl=10");

        assertThat(c1).isSameAs(c2);
    }
}
