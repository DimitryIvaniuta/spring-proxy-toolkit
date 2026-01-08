package com.github.dimitryivaniuta.gateway.infra;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@ActiveProfiles("test")
@TestPropertySource(properties = {
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "management.prometheus.metrics.export.enabled=true"
})
@Testcontainers(disabledWithoutDocker = true)
public abstract class BaseIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("gateway")
                    .withUsername("gateway")
                    .withPassword("gateway");

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("""
            DO $$
            BEGIN
              IF to_regclass('public.api_client_credential') IS NOT NULL THEN
                EXECUTE 'TRUNCATE TABLE api_client_credential RESTART IDENTITY CASCADE';
              END IF;
              IF to_regclass('public.api_client_policy') IS NOT NULL THEN
                EXECUTE 'TRUNCATE TABLE api_client_policy RESTART IDENTITY CASCADE';
              END IF;
              IF to_regclass('public.api_client') IS NOT NULL THEN
                EXECUTE 'TRUNCATE TABLE api_client RESTART IDENTITY CASCADE';
              END IF;
              IF to_regclass('public.idempotency_record') IS NOT NULL THEN
                EXECUTE 'TRUNCATE TABLE idempotency_record RESTART IDENTITY CASCADE';
              END IF;
              IF to_regclass('public.audit_call_log') IS NOT NULL THEN
                EXECUTE 'TRUNCATE TABLE audit_call_log RESTART IDENTITY CASCADE';
              END IF;
            END$$;
            """);
    }
}
