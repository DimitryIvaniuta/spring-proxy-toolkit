package com.github.dimitryivaniuta.gateway.sample.dto;

import java.time.Instant;

public record DemoRetryResponse(
        String status,
        int attempt,
        int failTimes,
        String subjectKey,
        Instant ts
) {}
