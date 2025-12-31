package com.github.dimitryivaniuta.gateway.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.gateway.proxy.audit.AuditCallLogService;
import com.github.dimitryivaniuta.gateway.proxy.audit.AuditMethodInterceptor;
import com.github.dimitryivaniuta.gateway.proxy.cache.CacheMethodInterceptor;
import com.github.dimitryivaniuta.gateway.proxy.idempotency.IdempotencyMethodInterceptor;
import com.github.dimitryivaniuta.gateway.proxy.idempotency.IdempotencyService;
import com.github.dimitryivaniuta.gateway.proxy.metrics.ProxyToolkitMetrics;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicyService;
import com.github.dimitryivaniuta.gateway.proxy.ratelimit.RateLimitKeyResolver;
import com.github.dimitryivaniuta.gateway.proxy.ratelimit.RateLimitMethodInterceptor;
import com.github.dimitryivaniuta.gateway.proxy.retry.RetryMethodInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

import static com.github.dimitryivaniuta.gateway.proxy.ProxyToolkitSupport.hasAnyProxyAnnotation;

/**
 * Creates a chained runtime proxy (ProxyFactory) for beans that use proxy-toolkit annotations.
 *
 * <p>This is NOT @Aspect-based AOP. It is a custom proxy wiring via BeanPostProcessor.
 *
 * <p>Advice order (outer -> inner):
 * <ol>
 *   <li>Audit: logs also short-circuits and failures</li>
 *   <li>Idempotency: short-circuit duplicate requests</li>
 *   <li>Cache: short-circuit read operations</li>
 *   <li>RateLimit: defense-in-depth (primary RL should be in API Gateway)</li>
 *   <li>Retry: retries only the actual backend execution</li>
 * </ol>
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@RequiredArgsConstructor
@EnableConfigurationProperties(ProxyToolkitProperties.class)
public final class ProxyToolkitBeanPostProcessor implements BeanPostProcessor {

    private final ProxyToolkitProperties props;

    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;

    private final AuditCallLogService auditService;
    private final IdempotencyService idempotencyService;

    private final RateLimitKeyResolver rateLimitKeyResolver;
    private final ApiClientPolicyService apiClientPolicyService;

    private final ProxyToolkitMetrics metrics;

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!props.isEnabled()) return bean;

        Class<?> targetClass = ClassUtils.getUserClass(bean);
        if (isExcluded(targetClass)) return bean;
        if (!needsProxy(targetClass)) return bean;

        // Build advices once per bean
        var audit = new AuditMethodInterceptor(auditService, objectMapper, props);

        // NOTE: ensure your IdempotencyMethodInterceptor constructor matches
        // (recommended signature: (IdempotencyService, ObjectMapper, ProxyToolkitMetrics))
        var idem = new IdempotencyMethodInterceptor(idempotencyService, objectMapper, rateLimitKeyResolver, apiClientPolicyService, metrics);

        // NOTE: ensure your CacheMethodInterceptor constructor matches
        // (recommended signature: (CacheManager, RateLimitKeyResolver, ProxyToolkitMetrics))
        var cache = new CacheMethodInterceptor(cacheManager, rateLimitKeyResolver, apiClientPolicyService, metrics);

        var rateLimit = new RateLimitMethodInterceptor(rateLimitKeyResolver, apiClientPolicyService, metrics);

        var retry = new RetryMethodInterceptor(rateLimitKeyResolver, apiClientPolicyService, metrics);

        // If already proxied (e.g., @Transactional), add advice to existing proxy
        if (bean instanceof Advised advised) {
            // add at index 0 in reverse order to preserve final outer->inner chain
            advised.addAdvice(0, retry);
            advised.addAdvice(0, rateLimit);
            advised.addAdvice(0, cache);
            advised.addAdvice(0, idem);
            advised.addAdvice(0, audit);
            return bean;
        }

        ProxyFactory pf = new ProxyFactory(bean);
        // allow class-based proxying for beans without interfaces
        pf.setProxyTargetClass(true);

        pf.addAdvice(audit);
        pf.addAdvice(idem);
        pf.addAdvice(cache);
        pf.addAdvice(rateLimit);
        pf.addAdvice(retry);

        return pf.getProxy();
    }

    private boolean needsProxy(Class<?> targetClass) {
        if (hasAnyProxyAnnotation(targetClass)) return true;
        for (Method m : targetClass.getMethods()) {
            if (hasAnyProxyAnnotation(m)) return true;
        }
        return false;
    }

    private boolean isExcluded(Class<?> targetClass) {
        String name = targetClass.getName();

        if (name.startsWith("org.springframework.") || name.startsWith("jakarta.") || name.startsWith("java.") || name.startsWith("kotlin.") || name.startsWith("com.zaxxer.")) {
            return true;
        }

        if (props.getExcludePackages() == null || props.getExcludePackages().isEmpty()) return false;

        for (String p : props.getExcludePackages()) {
            if (p == null || p.isBlank()) continue;
            String prefix = p.endsWith(".") ? p : p + ".";
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
