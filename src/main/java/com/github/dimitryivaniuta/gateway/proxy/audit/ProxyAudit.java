package com.github.dimitryivaniuta.gateway.proxy.audit;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ProxyAudit {
}
