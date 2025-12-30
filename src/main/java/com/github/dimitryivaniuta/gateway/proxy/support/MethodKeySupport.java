package com.github.dimitryivaniuta.gateway.proxy.support;

import java.lang.reflect.Method;

public final class MethodKeySupport {
    private MethodKeySupport() {}

    // Same string should be used everywhere (policy table, idempotency, etc.)
    public static String signature(Class<?> targetClass, Method method) {
        Class<?>[] p = method.getParameterTypes();
        StringBuilder sb = new StringBuilder(targetClass.getName())
                .append("#")
                .append(method.getName())
                .append("(");
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(p[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    // Shorter key for metrics tag (avoid long signatures)
    public static String metricMethodKey(Class<?> targetClass, Method method) {
        return targetClass.getSimpleName() + "#" + method.getName();
    }

    public static int clampInt(Integer v, int def, int min, int max) {
        if (v == null) return def;
        return Math.max(min, Math.min(max, v));
    }
}
