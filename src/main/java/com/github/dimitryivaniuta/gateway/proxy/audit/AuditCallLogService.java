package com.github.dimitryivaniuta.gateway.proxy.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists audit logs in an isolated transaction so business flows are not affected
 * by audit storage latency/failures.
 */
@Service
@RequiredArgsConstructor
public class AuditCallLogService {

    private final AuditCallLogRepository repo;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(AuditCallLog log) {
        repo.save(log);
    }
}
