package com.github.dimitryivaniuta.gateway.proxy.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dimitryivaniuta.gateway.proxy.ProxyToolkitProperties;
import com.github.dimitryivaniuta.gateway.proxy.annotations.ProxyAudit;
import com.github.dimitryivaniuta.gateway.proxy.support.MethodKeySupport;
import com.github.dimitryivaniuta.gateway.web.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.time.Instant;

@RequiredArgsConstructor
public final class AuditMethodInterceptor implements MethodInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuditMethodInterceptor.class);

    private final AuditCallLogService auditService;
    private final ObjectMapper mapper;
    private final ProxyToolkitProperties props;

    @Override
    public Object invoke(MethodInvocation inv) throws Throwable {
        ProxyAudit ann = find(inv.getThis().getClass(), inv.getMethod());
        if (ann == null || !ann.enabled()) {
            return inv.proceed();
        }

        Class<?> targetClass = resolveTargetClass(inv);
        if (isExcluded(targetClass)) {
            return inv.proceed();
        }

        final long startNs = System.nanoTime();

        final String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        final String traceId = MDC.get("traceId"); // optional; safe even if absent
        final String beanName = resolveBeanName(inv);

        final String methodSignature = MethodKeySupport.signature(targetClass, inv.getMethod());
        final int maxChars = resolveMaxPayloadChars(ann);

        final String argsJson = ann.captureArgs()
                ? truncateJsonSafe(safeWrite(inv.getArguments()), maxChars)
                : null;

        try {
            Object result = inv.proceed();
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;

            final String resultJson = (ann.captureResult() && inv.getMethod().getReturnType() != void.class)
                    ? truncateJsonSafe(safeWrite(result), maxChars)
                    : null;

            persistSafe(AuditCallLog.builder()
                    .correlationId(correlationId)
                    .traceId(traceId)
                    .beanName(beanName)
                    .targetClass(targetClass.getName())
                    .methodSignature(methodSignature)
                    .argsJson(argsJson)
                    .resultJson(resultJson)
                    .status(AuditCallLog.STATUS_OK)
                    .durationMs(durationMs)
                    .createdAt(Instant.now())
                    .build());

            return result;

        } catch (Throwable ex) {
            long durationMs = (System.nanoTime() - startNs) / 1_000_000L;

            final String errorStack = ann.captureStacktrace()
                    ? truncatePlain(stacktrace(ex), maxChars)
                    : null;

            persistSafe(AuditCallLog.builder()
                    .correlationId(correlationId)
                    .traceId(traceId)
                    .beanName(beanName)
                    .targetClass(targetClass.getName())
                    .methodSignature(methodSignature)
                    .argsJson(argsJson)
                    .resultJson(null)
                    .status(AuditCallLog.STATUS_ERROR)
                    .durationMs(durationMs)
                    .errorMessage(truncatePlain(ex.getMessage(), maxChars))
                    .errorStack(errorStack)
                    .createdAt(Instant.now())
                    .build());

            throw ex;
        }
    }

    private static Class<?> resolveTargetClass(MethodInvocation inv) {
        Object target = inv.getThis();
        Class<?> c = (target != null) ? AopUtils.getTargetClass(target) : null;
        return (c != null) ? c : inv.getMethod().getDeclaringClass();
    }

    private boolean isExcluded(Class<?> targetClass) {
        if (targetClass == null) return false;
        String name = targetClass.getName();

        if (props == null || props.getExcludePackages() == null) return false;

        for (String prefix : props.getExcludePackages()) {
            if (prefix != null && !prefix.isBlank() && name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String resolveBeanName(MethodInvocation inv) {
        // Best-effort: if you set it elsewhere into MDC.
        String fromMdc = MDC.get("beanName");
        if (fromMdc != null && !fromMdc.isBlank()) return fromMdc;

        // Fallback: target simple name
        Class<?> targetClass = resolveTargetClass(inv);
        return (targetClass != null) ? targetClass.getSimpleName() : "unknown";
    }

    private void persistSafe(AuditCallLog row) {
        try {
            auditService.save(row);
        } catch (Exception auditEx) {
            // Never break business flow due to audit persistence issues.
            log.warn("Audit persistence failed for methodSignature={}, reason={}",
                    row.getMethodSignature(), auditEx.toString());
        }
    }

    private int resolveMaxPayloadChars(ProxyAudit ann) {
        if (ann.maxPayloadChars() > 0) return ann.maxPayloadChars();
        if (props != null && props.getMaxPayloadChars() > 0) return props.getMaxPayloadChars();
        return 20_000;
    }

    private ProxyAudit find(Class<?> cls, Method m) {
        ProxyAudit onMethod = AnnotatedElementUtils.findMergedAnnotation(m, ProxyAudit.class);
        return (onMethod != null) ? onMethod : AnnotatedElementUtils.findMergedAnnotation(cls, ProxyAudit.class);
    }

    private String safeWrite(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            // must be valid jsonb → store json string
            return "\"<json-serialization-error>\"";
        }
    }

    /**
     * Ensures output is valid JSON for jsonb columns even when truncated.
     */
    private String truncateJsonSafe(String json, int maxChars) {
        if (json == null) return null;
        if (maxChars <= 0 || json.length() <= maxChars) return json;

        int previewLen = Math.min(maxChars, Math.min(json.length(), 10_000));
        String preview = json.substring(0, previewLen);

        ObjectNode node = mapper.createObjectNode();
        node.put("_truncated", true);
        node.put("_originalLength", json.length());
        node.put("_preview", preview);

        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            // fallback: json string
            return "\"<truncated>\"";
        }
    }

    private static String truncatePlain(String s, int maxChars) {
        if (s == null) return null;
        if (maxChars <= 0 || s.length() <= maxChars) return s;
        return s.substring(0, maxChars);
    }

    private static String stacktrace(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
