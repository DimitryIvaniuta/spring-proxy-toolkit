package com.github.dimitryivaniuta.gateway.proxy.policy;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "api_client_policy")
public class ApiClientPolicy {

    @EmbeddedId
    private ApiClientPolicyId id;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    // Rate limiting overrides
    @Column(name = "rl_permits_per_sec")
    private Integer rlPermitsPerSec;

    @Column(name = "rl_burst")
    private Integer rlBurst;

    // Retry overrides
    @Column(name = "retry_max_attempts")
    private Integer retryMaxAttempts;

    @Column(name = "retry_backoff_ms")
    private Integer retryBackoffMs;

    // Cache override
    @Column(name = "cache_ttl_seconds")
    private Integer cacheTtlSeconds;

    // Idempotency override
    @Column(name = "idempotency_ttl_seconds")
    private Integer idempotencyTtlSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
