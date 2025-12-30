package com.github.dimitryivaniuta.gateway.proxy.client;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ApiClientCredentialRepository extends JpaRepository<ApiClientCredential, Long> {

    Optional<ApiClientCredential> findByApiKeyHashAndEnabledTrue(String apiKeyHash);

    @Query("""
        select c
        from ApiClientCredential c
        join fetch c.client cl
        where c.apiKeyHash = :hash
          and c.enabled = true
          and cl.enabled = true
        """)
    Optional<ApiClientCredential> findActiveByApiKeyHash(@Param("hash") String hash);
}
