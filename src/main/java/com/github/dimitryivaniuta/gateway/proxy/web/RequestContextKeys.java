package com.github.dimitryivaniuta.gateway.proxy.web;


public final class RequestContextKeys {
    private RequestContextKeys() {}

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    public static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";
    public static final String IDEMPOTENCY_KEY_MDC_KEY = "idempotencyKey";
}
