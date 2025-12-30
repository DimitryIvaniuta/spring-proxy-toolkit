package com.github.dimitryivaniuta.gateway.proxy.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditCallLogRepository extends JpaRepository<AuditCallLog, Long> {
}
