package com.github.dimitryivaniuta.gateway.proxy.idempotency;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyIdempotent;
import com.github.dimitryivaniuta.gateway.proxy.metrics.ProxyToolkitMetrics;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicy;
import com.github.dimitryivaniuta.gateway.proxy.policy.ApiClientPolicyService;
import com.github.dimitryivaniuta.gateway.proxy.ratelimit.RateLimitKeyResolver;
import com.github.dimitryivaniuta.gateway.proxy.support.MethodKeySupport;
import com.github.dimitryivaniuta.gateway.web.CorrelationIdFilter;
import com.github.dimitryivaniuta.gateway.web.IdempotencyKeyFilter;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;

@RequiredArgsConstructor
public class IdempotencyMethodInterceptor implements MethodInterceptor {

    private final IdempotencyService service;
    private final ObjectMapper mapper;
    private final RateLimitKeyResolver keyResolver;
    private final ApiClientPolicyService policyService;
    private final ProxyToolkitMetrics metrics;

    // short poll to wait for another in-flight owner to finish
    private final Duration inFlightWaitMax = Duration.ofSeconds(2);
    private final Duration inFlightWaitStep = Duration.ofMillis(200);

    @Override
    public Object invoke(MethodInvocation inv) throws Throwable {
        Class<?> targetClass = resolveTargetClass(inv);
        Method specificMethod = AopUtils.getMostSpecificMethod(inv.getMethod(), targetClass);

        ProxyIdempotent ann = find(targetClass, specificMethod);
        if (ann == null || !ann.enabled()) return inv.proceed();

        // must come from IdempotencyKeyFilter (X-Idempotency-Key)
        String idemKey = MDC.get(IdempotencyKeyFilter.MDC_KEY);
        if (idemKey == null || idemKey.isBlank()) {
            if (ann.requireKey()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing X-Idempotency-Key");
            }
            return inv.proceed();
        }

        String fullMethodKey = MethodKeySupport.signature(targetClass, specificMethod);
        String metricMethodKey = MethodKeySupport.metricMethodKey(targetClass, specificMethod);

        // Policy lookup by subjectKey (apiKey:<hash> / user:<name> / ip:<addr>)
        RateLimitKeyResolver.ResolvedClient client = keyResolver.resolve();
        ApiClientPolicy policy = policyService.find(client.subjectKey(), fullMethodKey).orElse(null);

        // If policy disables for this client+method => skip idempotency
        if (policy != null && !policy.isEnabled()) return inv.proceed();

        Integer ttlOverride = (policy != null) ? policy.getIdempotencyTtlSeconds() : null;
        if (ttlOverride != null && ttlOverride <= 0) return inv.proceed();

        Duration ttl = (ttlOverride != null)
                ? Duration.ofSeconds(MethodKeySupport.clampInt(ttlOverride, (int) ann.ttlSeconds(), 60, 7 * 24 * 3600))
                : Duration.ofSeconds(ann.ttlSeconds());

        // Request hash must be stable; store minimal but deterministic hash
        String requestHash = sha256(safeArgsJson(inv.getArguments()));

        // lock owner should be correlation id (not idempotency key)
        String lockOwner = Optional.ofNullable(MDC.get(CorrelationIdFilter.MDC_KEY)).orElse("no-correlation");

        IdempotencyRecord rec = service.acquireOrGet(idemKey, fullMethodKey, requestHash, ttl, lockOwner);

        // Validate payload reuse for same key (unless disabled)
        if (ann.conflictOnDifferentRequest() && !requestHash.equals(rec.getRequestHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key reused with different request payload");
        }

        // Completed => serve stored response
        if (IdempotencyService.STATUS_COMPLETED.equals(rec.getStatus())) {
            metrics.idempotencyServed(metricMethodKey);
            return readStoredResult(inv, rec);
        }

        // Failed => conflict (caller can choose a new key)
        if (IdempotencyService.STATUS_FAILED.equals(rec.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Previous attempt failed for this idempotency key");
        }

        // Pending: if someone else owns lock and rejectInFlight => short wait then conflict
        if (IdempotencyService.STATUS_PENDING.equals(rec.getStatus())
                && ann.rejectInFlight()
                && rec.getLockedBy() != null
                && !lockOwner.equals(rec.getLockedBy())) {

            long deadline = System.nanoTime() + inFlightWaitMax.toNanos();
            while (System.nanoTime() < deadline) {
                Thread.sleep(inFlightWaitStep.toMillis());
                var updated = service.read(idemKey, fullMethodKey).orElse(null);
                if (updated == null) break;

                if (IdempotencyService.STATUS_COMPLETED.equals(updated.getStatus())) {
                    metrics.idempotencyServed(metricMethodKey);
                    return readStoredResult(inv, updated);
                }
                if (IdempotencyService.STATUS_FAILED.equals(updated.getStatus())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Previous attempt failed for this idempotency key");
                }
            }

            metrics.idempotencyInFlightConflict(metricMethodKey);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request with this idempotency key is already in progress");
        }

        metrics.idempotencyExecuted(metricMethodKey);

        try {
            Object result = inv.proceed();
            String responseJson = (specificMethod.getReturnType() == void.class) ? null : safeResultJson(result);
            service.markCompleted(idemKey, fullMethodKey, requestHash, responseJson);
            return result;
        } catch (Throwable ex) {
            service.markFailed(idemKey, fullMethodKey, requestHash, ex.getMessage());
            throw ex;
        }
    }

    private ProxyIdempotent find(Class<?> cls, Method m) {
        ProxyIdempotent onMethod = AnnotatedElementUtils.findMergedAnnotation(m, ProxyIdempotent.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(cls, ProxyIdempotent.class);
    }

    private Object readStoredResult(MethodInvocation inv, IdempotencyRecord rec) {
        if (inv.getMethod().getReturnType() == void.class) return null;
        String json = rec.getResponseJson();
        if (json == null || json.isBlank()) return null;

        try {
            JavaType type = mapper.getTypeFactory().constructType(inv.getMethod().getGenericReturnType());
            return mapper.readValue(json, type);
        } catch (Exception e) {
            // if cannot deserialize, fall back to executing (or raise)
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored idempotent response cannot be deserialized");
        }
    }

    private String safeArgsJson(Object[] args) {
        try {
            return mapper.writeValueAsString(args);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String safeResultJson(Object result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return "\"<json-serialization-error>\"";
        }
    }

    private static String sha256(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "sha256-error";
        }
    }

    private static Class<?> resolveTargetClass(MethodInvocation inv) {
        Object t = inv.getThis();
        Class<?> c = (t != null) ? AopUtils.getTargetClass(t) : null;
        return (c != null) ? c : inv.getMethod().getDeclaringClass();
    }
}
