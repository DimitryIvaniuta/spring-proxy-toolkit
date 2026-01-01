package com.github.dimitryivaniuta.gateway.sample.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record DemoIdempotentResponse(
        String paymentId,
        BigDecimal amount,
        String currency,
        String status,
        Instant createdAt
) {}
