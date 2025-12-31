package com.github.dimitryivaniuta.gateway.proxy.audit;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "audit_call_log")
public class AuditCallLog {

    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // timestamptz not null default now()
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "trace_id", length = 128)
    private String traceId;

    @Column(name = "bean_name", nullable = false, length = 255)
    private String beanName;

    @Column(name = "target_class", nullable = false, length = 512)
    private String targetClass;

    @Column(name = "method_signature", nullable = false, length = 1024)
    private String methodSignature;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "args", columnDefinition = "jsonb")
    private String argsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private String resultJson;

    @Column(name = "status", nullable = false, length = 32)
    private String status; // OK / ERROR

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "error_stack", columnDefinition = "text")
    private String errorStack;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
