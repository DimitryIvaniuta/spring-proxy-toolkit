package com.github.dimitryivaniuta.gateway.sample.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record DemoIdempotentRequest(
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency
) {}
