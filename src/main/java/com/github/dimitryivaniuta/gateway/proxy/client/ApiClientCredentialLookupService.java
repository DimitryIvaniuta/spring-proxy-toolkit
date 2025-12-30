package com.github.dimitryivaniuta.gateway.proxy.client;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ApiClientCredentialLookupService {

    private static final String CACHE = "apiKeyLookup:ttl=60"; // 60s cache (hits + misses)

    private final ApiClientCredentialRepository repo;
    private final CacheManager cacheManager;

    @SuppressWarnings("unchecked")
    public Optional<ApiClientCredential> findActiveByHash(String apiKeyHash) {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache != null) {
            Optional<ApiClientCredential> cached = cache.get(apiKeyHash, Optional.class);
            if (cached != null) return cached;
        }

        Optional<ApiClientCredential> loaded = repo.findActiveByApiKeyHash(apiKeyHash);

        if (cache != null) cache.put(apiKeyHash, loaded);
        return loaded;
    }
}
