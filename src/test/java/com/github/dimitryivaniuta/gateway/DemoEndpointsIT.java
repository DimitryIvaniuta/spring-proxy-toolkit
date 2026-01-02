package com.github.dimitryivaniuta.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.gateway.infra.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class DemoEndpointsIT extends BaseIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;

    private String apiKey;

    @BeforeEach
    void setupApiKey() throws Exception {
        if (apiKey != null) return;

        // 1) create client
        var createClientRes = mvc.perform(post("/api/admin/clients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientName":"TestClient","clientCode":"test-client"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode clientJson = om.readTree(createClientRes.getResponse().getContentAsString());
        long clientId = clientJson.get("id").asLong();

        // 2) create credential -> returns apiKeyRaw once
        var credRes = mvc.perform(post("/api/admin/clients/{id}/credentials", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"credentialName":"it-key"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode credJson = om.readTree(credRes.getResponse().getContentAsString());
        apiKey = credJson.get("apiKeyRaw").asText();

        assertThat(apiKey).isNotBlank();
    }

    @Test
    void cache_shouldReturnSameStableValueOnSecondCall() throws Exception {
        String v1 = readStableValue(
                mvc.perform(get("/api/demo/cache")
                                .queryParam("customerId", "42")
                                .header("X-Api-Key", apiKey))
                        .andExpect(status().isOk())
                        .andReturn()
        );

        String v2 = readStableValue(
                mvc.perform(get("/api/demo/cache")
                                .queryParam("customerId", "42")
                                .header("X-Api-Key", apiKey))
                        .andExpect(status().isOk())
                        .andReturn()
        );

        assertThat(v2).isEqualTo(v1);

        // sanity: audit logs should exist (if audit enabled on method)
        Integer auditCount = jdbc.queryForObject("select count(*) from audit_call_log", Integer.class);
        assertThat(auditCount).isNotNull();
        assertThat(auditCount).isGreaterThan(0);
    }

    @Test
    void idempotency_shouldReturnSamePaymentIdForSameKeyAndBody() throws Exception {
        String idemKey = "12345";

        String paymentId1 = readPaymentId(
                mvc.perform(post("/api/demo/idempotent")
                                .header("X-Api-Key", apiKey)
                                .header("X-Idempotency-Key", idemKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"amount":100,"currency":"PLN"}
                                        """))
                        .andExpect(status().isOk())
                        .andReturn()
        );

        String paymentId2 = readPaymentId(
                mvc.perform(post("/api/demo/idempotent")
                                .header("X-Api-Key", apiKey)
                                .header("X-Idempotency-Key", idemKey)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"amount":100,"currency":"PLN"}
                                        """))
                        .andExpect(status().isOk())
                        .andReturn()
        );

        assertThat(paymentId2).isEqualTo(paymentId1);

        // DB: one record for that key, completed, response_json stored
        Integer cnt = jdbc.queryForObject(
                "select count(*) from idempotency_record where idempotency_key = ?",
                Integer.class,
                idemKey
        );
        assertThat(cnt).isEqualTo(1);

        String status = jdbc.queryForObject(
                "select status from idempotency_record where idempotency_key = ?",
                String.class,
                idemKey
        );
        assertThat(status).isEqualTo("COMPLETED");

        String resp = jdbc.queryForObject(
                "select response_json from idempotency_record where idempotency_key = ?",
                String.class,
                idemKey
        );
        assertThat(resp).isNotBlank();
    }

    @Test
    void rateLimit_shouldReturn429AndRetryAfter() throws Exception {
        boolean got429 = false;

        for (int i = 0; i < 20; i++) {
            var res = mvc.perform(get("/api/demo/ratelimited").header("X-Api-Key", apiKey))
                    .andReturn();

            int code = res.getResponse().getStatus();
            if (code == 429) {
                got429 = true;

                String retryAfter = res.getResponse().getHeader("Retry-After");
                assertThat(retryAfter).isNotBlank();
                assertThat(Integer.parseInt(retryAfter)).isGreaterThanOrEqualTo(1);

                break;
            }

            // small delay to keep test stable across machines
            TimeUnit.MILLISECONDS.sleep(50);
        }

        assertThat(got429).isTrue();
    }

    @Test
    void retry_shouldEventuallySucceed() throws Exception {
        var res = mvc.perform(get("/api/demo/retry")
                        .queryParam("failTimes", "2")
                        .header("X-Api-Key", apiKey))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = om.readTree(res.getResponse().getContentAsString());
        assertThat(json.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(json.get("attempt").asInt()).isGreaterThanOrEqualTo(3); // failTimes=2 -> succeed on >=3
    }

    @Test
    void actuator_shouldBeReachable() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk());

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    private String readStableValue(org.springframework.test.web.servlet.MvcResult r) throws Exception {
        JsonNode json = om.readTree(r.getResponse().getContentAsString());
        return json.get("stableValue").asText();
    }

    private String readPaymentId(org.springframework.test.web.servlet.MvcResult r) throws Exception {
        JsonNode json = om.readTree(r.getResponse().getContentAsString());
        return json.get("paymentId").asText();
    }
}
