# spring-proxy-toolkit (Java 21) — ProxyFactory Toolkit for Audit / Cache / Idempotency / RateLimit / Retry

A production-minded **Spring Boot (MVC)** project that demonstrates a **custom ProxyFactory-based toolkit**: annotate your service methods and apply a deterministic chain of interceptors (without AspectJ) to add cross‑cutting behavior.

This repository is intentionally **backend-only** and keeps infra minimal:
- ✅ PostgreSQL + Flyway + JPA
- ✅ Caffeine (local cache) — **no Redis, no Bucket4j**
- ✅ Resilience4j (Retry + RateLimiter)
- ✅ Micrometer + Actuator (+ Prometheus)

---

## What you get

### Cross-cutting features
- **Audit** (`@ProxyAudit`)  
  Persists method call logs to PostgreSQL (duration, status, error, correlation id, JSON payloads).
- **Cache** (`@ProxyCache`)  
  Caffeine caching with **per-cache TTL** via cache name convention: `cacheName:ttl=60`.
- **Idempotency** (`@ProxyIdempotent`)  
  DB-backed idempotency via `X-Idempotency-Key`. Stores response JSON and **returns it** on repeats.
- **Rate limiting** (`@ProxyRateLimit`)  
  In-process, defense-in-depth rate limiting via **Resilience4j**. (Primary RL should be enforced at API Gateway.)
- **Retry** (`@ProxyRetry`)  
  Resilience4j retries with exponential backoff and local jitter.

### Operational features
- **Consistent error JSON** via `GlobalExceptionHandler`  
  Includes `correlationId` and sets **`Retry-After`** header for 429 responses.
- **Metrics** (`ProxyToolkitMetrics`) via Micrometer counters/timers  
  Exposed through Actuator (`/actuator/metrics`, `/actuator/prometheus`).

---

## High-level architecture

### Request flow
1. HTTP request hits the app (Spring MVC)
2. Servlet filters populate MDC:
    - `CorrelationIdFilter`
        - reads or generates `X-Correlation-Id`
        - stores in MDC as `correlationId`
    - `IdempotencyKeyFilter`
        - reads `X-Idempotency-Key`
        - stores in MDC as `idempotencyKey`
3. Controller calls service method
4. `ProxyToolkitBeanPostProcessor` wraps target beans using `ProxyFactory` and applies interceptors (chain order):
    1) `AuditMethodInterceptor`
    2) `IdempotencyMethodInterceptor`
    3) `CacheMethodInterceptor`
    4) `RateLimitMethodInterceptor`
    5) `RetryMethodInterceptor`
5. Response returned; exceptions handled by `GlobalExceptionHandler`

---

## Tech stack

- Java **21**
- Spring Boot **3.x** (Gradle plugin + BOM-managed dependencies)
- Spring MVC + Spring AOP (`ProxyFactory`, `MethodInterceptor`)
- PostgreSQL + Flyway + Spring Data JPA (Hibernate)
- Caffeine cache (custom TTL manager)
- Resilience4j RateLimiter + Retry
- Micrometer + Actuator (+ Prometheus registry)
- Lombok

> Note: This project does **not** use the legacy `io.spring.dependency-management` plugin.  
> Spring Boot’s Gradle plugin manages dependency versions via its BOM.

---

## Project layout (main modules)

```
src/main/java/com/github/dimitryivaniuta/gateway
  config/
    CacheConfig.java              # CacheManager bean (TtlCaffeineCacheManager)
    JacksonConfig.java            # ObjectMapper tuning for JSONB, stable serialization
  proxy/
    annotations/                  # @ProxyAudit/@ProxyCache/@ProxyIdempotent/@ProxyRateLimit/@ProxyRetry
    audit/                        # AuditCallLog entity + repository + interceptor
    cache/                        # CacheMethodInterceptor + TtlCaffeineCacheManager
    client/                       # ApiClient + ApiClientCredential + admin controller + hash services
    idempotency/                  # IdempotencyRecord + repo + service + interceptor + cleanup job
    metrics/                      # ProxyToolkitMetrics (Micrometer)
    policy/                       # ApiClientPolicy entity (composite key) + repo + service
    ratelimit/                    # RateLimitKeyResolver + interceptor + exception
    retry/                        # RetryMethodInterceptor
    support/                      # BeanPostProcessor + properties + helper utilities
  sample/
    DemoController.java           # /api/demo/* endpoints
    DemoService.java              # annotated methods to exercise toolkit
    dto/                          # demo DTOs
  web/
    CorrelationIdFilter.java
    IdempotencyKeyFilter.java
    GlobalExceptionHandler.java
    RequestContextKeys.java       # (if present) MDC key constants
  GatewayProxyToolkitApplication.java
```

---

## Database migrations (Flyway)

Location: `src/main/resources/db/migration/`

Included migrations (typical):
- `V1__create_audit_call_log.sql`
- `V2__create_idempotency_record.sql`
- `V3__idempotency_hardening.sql`
- `V4__create_api_client_policy.sql`
- `V5__create_api_client_and_credentials.sql`
- `V6__api_client_credentials_on_delete_cascade.sql`

---

## Local run

### Prerequisites
- Java 21
- Docker + Docker Compose

### Start PostgreSQL
Your docker-compose maps PostgreSQL to local port **5446**:

```bash
docker compose up -d
```

> Recommendation: pin Postgres to a stable major version (e.g. `postgres:16.x`), avoid `postgres:latest`.

### Run the app
```bash
./gradlew bootRun
```

### Verify
- Health: `GET http://localhost:8080/actuator/health`
- Metrics list: `GET http://localhost:8080/actuator/metrics`
- Prometheus: `GET http://localhost:8080/actuator/prometheus`

---

## Headers

- `X-Correlation-Id` (optional)  
  If absent, the backend generates one and returns it in the response.
- `X-Idempotency-Key` (required for `@ProxyIdempotent(requireKey = true)`)  
  Must be **stable per user action** (reused for retries).
- `X-Api-Key`  
  Identifies the API client (used for policy/rate limit scoping).

---

## Demo endpoints (`/api/demo/*`)

These endpoints exist to test the toolkit quickly.

### Cache
```http
GET /api/demo/cache?customerId=42
X-Api-Key: <apiKey>
```
- 1st call: cache MISS (new UUID)
- 2nd call: cache HIT (same UUID)

### Idempotency
```http
POST /api/demo/idempotent
X-Api-Key: <apiKey>
X-Idempotency-Key: 12345
Content-Type: application/json

{ "amount": 100, "currency": "PLN" }
```
- 1st call: executes business logic; stores response JSON in DB
- 2nd call (same key + same body): returns **stored response** (same `paymentId`)

### Rate limit
```http
GET /api/demo/ratelimited
X-Api-Key: <apiKey>
```
Call quickly multiple times → eventually returns:
- HTTP **429**
- Header: `Retry-After: <seconds>`
- JSON error from `GlobalExceptionHandler`

### Retry
```http
GET /api/demo/retry?failTimes=2
X-Api-Key: <apiKey>
```
Simulates transient failure first N times and succeeds after retries.

---

## Admin endpoints (API clients + credentials)

Used to create a client and obtain an API key.

Expected endpoints:
- `POST /api/admin/clients`
- `GET  /api/admin/clients`
- `POST /api/admin/clients/{clientId}/credentials`
- `GET  /api/admin/clients/{clientId}/credentials`
- `DELETE /api/admin/clients/{clientId}`

Credential creation returns the **raw API key once** (store it securely).  
DB uses **ON DELETE CASCADE** so removing a client removes its credentials.

---

## Policy overrides (per client + method)

Entity: `ApiClientPolicy` with composite key `ApiClientPolicyId(clientKey, methodKey)`.

- `clientKey` is derived by your resolver (commonly `apiKey:<rawApiKey>`).  
  The admin credential endpoint returns `policyClientKey` to avoid guessing.
- `methodKey` is the resolved method signature (generated by `MethodKeySupport`).

Override fields (examples):
- `enabled=false` → skip toolkit for that client+method
- `cacheTtlSeconds=0` → disable caching for that client+method
- `idempotencyTtlSeconds=0` → disable idempotency for that client+method
- rate limit overrides (if enabled in your policy model)

---

## Metrics (Micrometer)

Metrics are emitted by `ProxyToolkitMetrics` and exposed via Actuator.

Typical counter names used in tests:
- `proxy_toolkit_cache_hits_total`
- `proxy_toolkit_cache_misses_total`
- `proxy_toolkit_idempotency_executed_total`
- `proxy_toolkit_idempotency_served_total`
- `proxy_toolkit_ratelimit_rejected_total`
- `proxy_toolkit_retry_calls_total`
- `proxy_toolkit_retry_attempts_total`

> Exact tags may vary (method/cacheName/clientKey). The integration tests sum counters by name.

---

## Postman

A Postman collection is provided (import it into Postman):
- `postman/spring-proxy-toolkit.postman_collection.json`
  (or in repo root: `spring-proxy-toolkit.postman_collection.json` depending on your layout)

It includes:
- create client
- create credential (captures `apiKeyRaw` into env)
- call demo endpoints (cache/idempotent/ratelimit/retry)
- actuator checks

---

## Testing

### Test strategy
- **Integration tests** use **Testcontainers Postgres** + `@SpringBootTest` + `MockMvc`.
- Flyway runs against the container; Hibernate uses `ddl-auto=validate`.

### Run tests
```bash
./gradlew test
```

### Essential test classes (recommended)
- `ProxyToolkitMetricsIT`  
  Verifies Micrometer counters increment after calling endpoints.
- `PolicyOverridesIT`  
  Inserts `ApiClientPolicy` records and verifies behavior:
    - disable cache
    - disable idempotency
    - disable rate limit via `enabled=false`
- `IdempotencyConcurrencyIT`  
  Two threads send the same `X-Idempotency-Key` concurrently → **single execution** and same response.

> Integration tests do **not** require `docker compose up` because they start Postgres using Testcontainers.

---

## Troubleshooting

### Cache: “expireAfterWrite was already set …”
Cause: reusing a mutable Caffeine builder across caches.  
Fix: `TtlCaffeineCacheManager` must create a **fresh builder per cache** (uses `Supplier<Caffeine<..>>`).

### Idempotency returns different response for same key
Most common causes:
- missing `X-Idempotency-Key`
- `IdempotencyKeyFilter` not registered
- interceptor reading wrong MDC key (must use `IdempotencyKeyFilter.MDC_KEY`)
- request body differs → request hash differs (if `conflictOnDifferentRequest=true`, you’ll get 409)

### Flyway “Unsupported Database” on newer Postgres
If you run Postgres 17+, ensure:
- `org.flywaydb:flyway-database-postgresql` is on the classpath
- or pin Postgres to 16.x

### No exceptions printed in console
Ensure:
- logging pattern includes stacktraces (e.g. `%wEx`)
- your `GlobalExceptionHandler` logs the exception (`log.error("Unhandled exception", ex)`)

---

## Security notes
- API keys must be stored **hashed** in DB (plus server-side pepper/secret).
- Raw API key is returned **only once** on credential creation.

---

## License
Internal/demo project.
This project is licensed under the [MIT License](LICENSE).

---

## Contact

**Dzmitry Ivaniuta** — [dzmitry.ivaniuta.services@gmail.com](mailto:dzmitry.ivaniuta.services@gmail.com) — [GitHub](https://github.com/DimitryIvaniuta)
