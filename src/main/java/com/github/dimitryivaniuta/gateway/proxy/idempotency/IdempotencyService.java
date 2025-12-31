package com.github.dimitryivaniuta.gateway.proxy.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private final IdempotencyRecordRepository repo;

    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> read(String key, String methodKey) {
        return repo.findByIdempotencyKeyAndMethodKey(key, methodKey);
    }

    /**
     * Acquire record for this key+method:
     * - creates new PENDING if absent
     * - if expired, resets to PENDING
     * - validates requestHash consistency (handled by interceptor based on annotation flags)
     *
     * Uses PESSIMISTIC_WRITE to safely coordinate concurrent requests.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyRecord acquireOrGet(String key,
                                          String methodKey,
                                          String requestHash,
                                          Duration ttl,
                                          String lockOwner) {

        Instant now = Instant.now();

        var existing = repo.findForUpdate(key, methodKey);
        if (existing.isEmpty()) {
            IdempotencyRecord created = IdempotencyRecord.builder()
                    .idempotencyKey(key)
                    .methodKey(methodKey)
                    .requestHash(requestHash)
                    .status(STATUS_PENDING)
                    .expiresAt(now.plus(ttl))
                    .lockedAt(now)
                    .lockedBy(lockOwner)
                    .build();
            return repo.save(created);
        }

        IdempotencyRecord rec = existing.get();

        // Expired → reuse slot
        if (rec.isExpired(now)) {
            rec.setRequestHash(requestHash);
            rec.setStatus(STATUS_PENDING);
            rec.setResponseJson(null);
            rec.setErrorMessage(null);
            rec.setExpiresAt(now.plus(ttl));
            rec.setLockedAt(now);
            rec.setLockedBy(lockOwner);
            return repo.save(rec);
        }

        // If currently PENDING and unlocked, take lock
        if (STATUS_PENDING.equals(rec.getStatus()) && (rec.getLockedBy() == null || rec.getLockedBy().isBlank())) {
            rec.setLockedAt(now);
            rec.setLockedBy(lockOwner);
            return repo.save(rec);
        }

        return rec;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(String key, String methodKey, String requestHash, String responseJson) {
        IdempotencyRecord rec = repo.findForUpdate(key, methodKey).orElseThrow();
        rec.setRequestHash(requestHash);
        rec.setStatus(STATUS_COMPLETED);
        rec.setResponseJson(responseJson);
        rec.setErrorMessage(null);
        rec.setLockedAt(null);
        rec.setLockedBy(null);
        repo.save(rec);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String key, String methodKey, String requestHash, String errorMessage) {
        IdempotencyRecord rec = repo.findForUpdate(key, methodKey).orElseThrow();
        rec.setRequestHash(requestHash);
        rec.setStatus(STATUS_FAILED);
        rec.setErrorMessage(errorMessage);
        rec.setLockedAt(null);
        rec.setLockedBy(null);
        repo.save(rec);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredNow() {
        return repo.deleteExpired(Instant.now());
    }
}
