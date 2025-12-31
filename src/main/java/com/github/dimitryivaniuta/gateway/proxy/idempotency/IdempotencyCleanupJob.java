package com.github.dimitryivaniuta.gateway.proxy.idempotency;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically deletes expired idempotency records (expires_at < now).
 *
 * IMPORTANT:
 * Ensure scheduling is enabled in your app (e.g., add @EnableScheduling on your main application class).
 */
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyService service;

    @Scheduled(cron = "0 */10 * * * *") // every 10 minutes
    public void cleanupExpired() {
        int deleted = service.deleteExpiredNow();
        if (deleted > 0) {
            log.info("Idempotency cleanup deleted {} expired records", deleted);
        }
    }
}
