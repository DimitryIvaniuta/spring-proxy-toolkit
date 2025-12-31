package com.github.dimitryivaniuta.gateway.proxy;

import com.github.dimitryivaniuta.gateway.proxy.annotations.*;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.AnnotatedElement;

public final class ProxyToolkitSupport {
    private ProxyToolkitSupport() {
    }

    public static boolean hasAnyProxyAnnotation(AnnotatedElement el) {
        return AnnotatedElementUtils.hasAnnotation(el, ProxyAudit.class)
                || AnnotatedElementUtils.hasAnnotation(el, ProxyIdempotent.class)
                || AnnotatedElementUtils.hasAnnotation(el, ProxyCache.class)
                || AnnotatedElementUtils.hasAnnotation(el, ProxyRateLimit.class)
                || AnnotatedElementUtils.hasAnnotation(el, ProxyRetry.class);
    }
}
