package com.github.dimitryivaniuta.gateway.sample.dto;

import java.time.Instant;

public record DemoRateLimitedResponse(
        String message,
        Instant ts
) {}
