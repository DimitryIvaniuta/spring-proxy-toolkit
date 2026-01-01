package com.github.dimitryivaniuta.gateway.sample.dto;

import java.time.Instant;

public record DemoCacheResponse(
        Long customerId,
        String stableValue,
        Instant generatedAt
) {}
