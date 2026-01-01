package com.github.dimitryivaniuta.gateway.proxy.cache;

import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyCache;
import com.github.dimitryivaniuta.gateway.proxy.metrics.ProxyToolkitMetrics;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicy;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicyService;
import com.github.dimitryivaniuta.gateway.proxy.ratelimit.RateLimitKeyResolver;
import com.github.dimitryivaniuta.gateway.proxy.support.MethodKeySupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
import java.util.Arrays;

@RequiredArgsConstructor
@Slf4j
public class CacheMethodInterceptor implements MethodInterceptor {

    private final CacheManager cacheManager;
    private final RateLimitKeyResolver keyResolver;
    private final ApiClientPolicyService policyService;
    private final ProxyToolkitMetrics metrics;

    @Override
    public Object invoke(MethodInvocation inv) throws Throwable {
        ProxyCache ann = find(inv.getThis().getClass(), inv.getMethod());
        if (ann == null || !ann.enabled()) return inv.proceed();
        if (inv.getMethod().getReturnType() == void.class) return inv.proceed();

        final Class<?> targetClass = resolveTargetClass(inv);
        final String fullMethodKey = MethodKeySupport.signature(targetClass, inv.getMethod());
        final String metricMethodKey = MethodKeySupport.metricMethodKey(targetClass, inv.getMethod());

        // Resolve subject (may be null depending on your resolver impl)
        RateLimitKeyResolver.ResolvedClient client;
        try {
            client = keyResolver.resolve();
        } catch (Exception ex) {
            // defense: never break business path due to cache infra
            log.debug("Cache skipped (keyResolver failed) for {}: {}", metricMethodKey, ex.toString());
            return inv.proceed();
        }

        final String subjectKey = (client != null && client.subjectKey() != null && !client.subjectKey().isBlank())
                ? client.subjectKey()
                : "anonymous";

        // Policy (optional)
        ApiClientPolicy policy = null;
        try {
            if (client != null && client.subjectKey() != null && !client.subjectKey().isBlank()) {
                policy = policyService.find(client.subjectKey(), fullMethodKey).orElse(null);
            }
        } catch (Exception ex) {
            log.debug("Cache policy lookup skipped for {}: {}", metricMethodKey, ex.toString());
        }

        if (policy != null && !policy.isEnabled()) return inv.proceed();

        Integer ttlOverride = (policy != null) ? policy.getCacheTtlSeconds() : null;
        if (ttlOverride != null && ttlOverride <= 0) return inv.proceed(); // disabled by policy

        long ttl = (ttlOverride != null)
                ? MethodKeySupport.clampInt(ttlOverride, (int) ann.ttlSeconds(), 1, 3600)
                : ann.ttlSeconds();

        final String cacheName = ann.cacheName() + ":ttl=" + ttl;

        Cache cache;
        try {
            cache = cacheManager.getCache(cacheName);
        } catch (Exception ex) {
            log.debug("Cache skipped (cacheManager.getCache failed) for {}: {}", metricMethodKey, ex.toString());
            return inv.proceed();
        }
        if (cache == null) return inv.proceed();

        CacheKey key = new CacheKey(fullMethodKey, Arrays.deepHashCode(inv.getArguments()), subjectKey);

        try {
            Cache.ValueWrapper hit = cache.get(key);
            if (hit != null) {
                metrics.cacheHit(cacheName, metricMethodKey);
                return hit.get();
            }

            metrics.cacheMiss(cacheName, metricMethodKey);
            Object result = inv.proceed();

            // usually avoid caching nulls
            if (result != null) {
                cache.put(key, result);
            }
            return result;

        } catch (Exception ex) {
            // NEVER turn caching into 500
            log.debug("Cache skipped (get/put failed) for {}: {}", metricMethodKey, ex.toString());
            return inv.proceed();
        }
    }

    private static ProxyCache find(Class<?> cls, Method m) {
        ProxyCache onMethod = AnnotatedElementUtils.findMergedAnnotation(m, ProxyCache.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(cls, ProxyCache.class);
    }

    private static Class<?> resolveTargetClass(MethodInvocation inv) {
        Object t = inv.getThis();
        Class<?> c = (t != null) ? AopUtils.getTargetClass(t) : null;
        return (c != null) ? c : inv.getMethod().getDeclaringClass();
    }

    public record CacheKey(String methodKey, int argsHash, String clientKey) {}
}
