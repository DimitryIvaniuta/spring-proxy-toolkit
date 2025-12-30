package com.github.dimitryivaniuta.gateway.proxy.client;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiClientRepository extends JpaRepository<ApiClient, Long> {
    Optional<ApiClient> findByClientCode(String clientCode);
}
