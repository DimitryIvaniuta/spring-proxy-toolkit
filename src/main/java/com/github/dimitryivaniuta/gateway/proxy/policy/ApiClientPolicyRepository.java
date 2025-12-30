package com.github.dimitryivaniuta.gateway.proxy.policy;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiClientPolicyRepository extends JpaRepository<ApiClientPolicy, ApiClientPolicyId> {
}
