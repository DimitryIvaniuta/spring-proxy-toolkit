package com.github.dimitryivaniuta.gateway.proxy.idempotency;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKeyAndMethodKey(String key, String methodKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from IdempotencyRecord r
            where r.idempotencyKey = :k and r.methodKey = :m
            """)
    Optional<IdempotencyRecord> findForUpdate(@Param("k") String key, @Param("m") String methodKey);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from IdempotencyRecord r
            where r.expiresAt is not null and r.expiresAt < :now
            """)
    int deleteExpired(@Param("now") Instant now);
}
