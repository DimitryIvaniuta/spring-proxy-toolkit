package com.github.dimitryivaniuta.gateway.proxy.policy;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ApiClientPolicyService {

    private static final String CACHE_NAME = "apiClientPolicy:ttl=30"; // 30s is enough; cache hits+misses

    private final ApiClientPolicyRepository repo;
    private final CacheManager cacheManager;

    @SuppressWarnings("unchecked")
    public Optional<ApiClientPolicy> find(String clientKey, String methodKey) {
        ApiClientPolicyId id = new ApiClientPolicyId(clientKey, methodKey);

        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            Optional<ApiClientPolicy> cached = cache.get(id, Optional.class);
            if (cached != null) {
                return cached;
            }
        }

        Optional<ApiClientPolicy> loaded = repo.findById(id);

        if (cache != null) {
            cache.put(id, loaded); // caches empty Optional too (prevents DB storms)
        }
        return loaded;
    }
}
