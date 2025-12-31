package com.github.dimitryivaniuta.gateway.proxy.cache;

import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyCache;
import com.github.dimitryivaniuta.gateway.proxy.metrics.ProxyToolkitMetrics;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicy;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicyService;
import com.github.dimitryivaniuta.gateway.proxy.ratelimit.RateLimitKeyResolver;
import com.github.dimitryivaniuta.gateway.proxy.support.MethodKeySupport;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

@RequiredArgsConstructor
public class CacheMethodInterceptor implements MethodInterceptor {

    private final CacheManager cacheManager;
    private final RateLimitKeyResolver keyResolver;
    private final ApiClientPolicyService policyService;
    private final ProxyToolkitMetrics metrics;

    @Override
    public Object invoke(MethodInvocation inv) throws Throwable {
        ProxyCache ann = find(inv.getThis().getClass(), inv.getMethod());
        if (ann == null) return inv.proceed();
        if (inv.getMethod().getReturnType() == void.class) return inv.proceed();

        var client = keyResolver.resolve();
        String fullMethodKey = MethodKeySupport.signature(inv.getThis().getClass(), inv.getMethod());
        String metricMethodKey = MethodKeySupport.metricMethodKey(inv.getThis().getClass(), inv.getMethod());

        ApiClientPolicy policy = policyService.find(client.subjectKey(), fullMethodKey).orElse(null);
        if (policy != null && !policy.isEnabled()) {
            return inv.proceed();
        }

        Integer ttlOverride = (policy != null) ? policy.getCacheTtlSeconds() : null;
        if (ttlOverride != null && ttlOverride <= 0) {
            return inv.proceed(); // caching disabled by policy
        }

        long ttl = (ttlOverride != null)
                ? MethodKeySupport.clampInt(ttlOverride, (int) ann.ttlSeconds(), 1, 3600)
                : ann.ttlSeconds();

        String cacheName = ann.cacheName() + ":ttl=" + ttl;
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) return inv.proceed();

        // stable key (no JSON stringify); include subject type to prevent leakage across clients
        CacheKey key = new CacheKey(fullMethodKey, Arrays.deepHashCode(inv.getArguments()), client.subjectKey());

        Cache.ValueWrapper hit = cache.get(key);
        if (hit != null) {
            metrics.cacheHit(cacheName, metricMethodKey);
            return hit.get();
        }

        metrics.cacheMiss(cacheName, metricMethodKey);
        Object result = inv.proceed();
        cache.put(key, result);
        return result;
    }

    private static ProxyCache find(Class<?> cls, Method m) {
        ProxyCache onMethod = AnnotatedElementUtils.findMergedAnnotation(m, ProxyCache.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(cls, ProxyCache.class);
    }

    public record CacheKey(String methodKey, int argsHash, String clientKey) {}
}
