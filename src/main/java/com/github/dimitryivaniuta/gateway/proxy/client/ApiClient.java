package com.github.dimitryivaniuta.gateway.proxy.client;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "api_client")
public class ApiClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_name", nullable = false)
    private String clientName;

    @Column(name = "client_code", nullable = false, unique = true, length = 64)
    private String clientCode;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @OneToMany(
            mappedBy = "client",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @Builder.Default
    private List<ApiClientCredential> credentials = new ArrayList<>();

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

    // ---- relationship helpers (important for orphanRemoval correctness)
    public void addCredential(ApiClientCredential c) {
        credentials.add(c);
        c.setClient(this);
    }

    public void removeCredential(ApiClientCredential c) {
        credentials.remove(c);
        c.setClient(null);
    }
}
