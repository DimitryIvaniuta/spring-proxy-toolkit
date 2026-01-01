# Spring Proxy Toolkit (Java 21) — Audit / Cache / Idempotency / Rate Limit / Retry

A production-minded **Spring Boot 3.x** project that demonstrates a **custom ProxyFactory-based “toolkit”**: you annotate service methods and get cross-cutting behavior via chained method interceptors:

- **Audit**: persist call logs to PostgreSQL (JSONB args/result)
- **Caching**: local Caffeine caching with per-cache TTL (`cacheName:ttl=60`)
- **Idempotency**: DB-backed idempotency using `X-Idempotency-Key` (stores response JSONB and returns it on repeats)
- **Rate limiting**: in-service, defense-in-depth rate limiting via **Resilience4j** (primary RL should live in API Gateway)
- **Retry**: Resilience4j retries with exponential backoff + local jitter

The project is intentionally **backend-only** (no Redis/Bucket4j, no Spring Cloud Gateway inside this app).

---

## Tech stack

- Java **21**
- Spring Boot **3.x**
- Spring MVC, Spring AOP (`ProxyFactory`)
- PostgreSQL + JPA (Hibernate) + Flyway
- Caffeine cache
- Resilience4j (Retry + RateLimiter)
- Micrometer + Actuator (+ Prometheus registry)
- Lombok

---

## Architecture (high level)

### Runtime flow
1. **HTTP request** enters the app
2. Servlet **filters** populate MDC context:
    - `CorrelationIdFilter` → `correlationId` (and `X-Correlation-Id`)
    - `IdempotencyKeyFilter` → `idempotencyKey` from `X-Idempotency-Key`
3. Controller calls your service method
4. `ProxyToolkitBeanPostProcessor` wraps the service bean in a **ProxyFactory** and applies chained interceptors:
    1. `AuditMethodInterceptor`
    2. `IdempotencyMethodInterceptor`
    3. `CacheMethodInterceptor`
    4. `RateLimitMethodInterceptor`
    5. `RetryMethodInterceptor`
5. Response returns, errors are handled by a global exception handler (consistent JSON, `Retry-After` for 429)

### Why ProxyFactory instead of @Aspect?
- This project demonstrates **explicit interceptor chaining** and policy-driven behavior, which is easier to reason about and test as a “toolkit”.
- You can still migrate to AspectJ-style aspects later if you prefer—interceptors already encapsulate the logic.

---

## Features

### 1) Audit (DB)
- Stores: correlation id, bean/class/method signature, duration, status, error message/stack, JSONB args/result.
- Persistence is in a **separate transaction** (`REQUIRES_NEW`) so audit failures don’t break business flows.

### 2) Cache (Caffeine)
- Local in-memory cache with `CacheManager`.
- **Per-cache TTL** by cache name suffix:
    - `demoCustomerCache:ttl=60`
- Safe fallback behavior: if caching fails, the request continues without crashing.

### 3) Idempotency (DB + X-Idempotency-Key)
- Client sends `X-Idempotency-Key` for a POST/PUT.
- Backend stores the **response JSONB** in `idempotency_record` with status `PENDING|COMPLETED|FAILED`.
- Repeating the same key + method returns the stored response instead of executing the business method again.
- Uses **pessimistic locking** (`SELECT ... FOR UPDATE`) to prevent double execution under concurrency.
- TTL cleanup job deletes expired records.

### 4) Rate limiting (Resilience4j)
- In-process rate limiting (defense-in-depth).
- For real public APIs, do **primary rate limiting at the edge** (API Gateway) and keep this as a safety net.

### 5) Retry (Resilience4j)
- Retries transient failures (configurable via annotation).
- Exponential backoff + local jitter wrapper.

---

## Local run

### Prerequisites
- Java 21
- Docker + Docker Compose
- (Optional) Postman

### 1) Start PostgreSQL
This project expects Postgres exposed on **5446** (adjust if you use a different port):

```bash
docker compose up -d
```

> **Important:** don’t use `postgres:latest`. Pin a major version (e.g. 16.x) to avoid unexpected upgrades.

### 2) Run the app
```bash
./gradlew bootRun
```

### 3) Verify health
- `GET http://localhost:8080/actuator/health`
- `GET http://localhost:8080/actuator/metrics`
- `GET http://localhost:8080/actuator/prometheus`

---

## Configuration

### `application.yml` (typical)
Key sections:
- `spring.datasource.*` points to Postgres
- `spring.jpa.hibernate.ddl-auto: validate` (schema must match migrations)
- `spring.flyway.enabled: true`
- `spring.cache.type: caffeine`
- `proxy-toolkit.*` enables the toolkit and excludes packages
- `management.endpoints.web.exposure.include` exposes health/metrics/prometheus

> If Flyway reports **Unsupported Database** for Postgres 17+:
> add dependency `org.flywaydb:flyway-database-postgresql` and/or pin Postgres to 16.x.

---

## Headers used

- `X-Correlation-Id`  
  Optional. If absent, backend generates one and returns it in the response header.

- `X-Idempotency-Key`  
  Required for methods annotated with `@ProxyIdempotent(requireKey = true)`.

- `X-Api-Key`  
  Used by your client identity resolver / policy lookups (admin endpoints generate API keys).

---

## Demo endpoints (for Postman)

These endpoints are designed to exercise all interceptors:

### Cache
```http
GET /api/demo/cache?customerId=42
```
- First call: cache miss (new UUID)
- Second call: cache hit (same UUID)

### Idempotency
```http
POST /api/demo/idempotent
X-Idempotency-Key: 12345
Content-Type: application/json
{ "amount": 100, "currency": "PLN" }
```
- First call: executes and stores response
- Second call with same key + same body: returns stored response (same `paymentId`)

### Rate limit
```http
GET /api/demo/ratelimited
```
Call it quickly multiple times → should produce **429** with `Retry-After`.

### Retry
```http
GET /api/demo/retry?failTimes=2
```
Simulates transient failures for the first N calls and then succeeds (interceptor retries automatically).

---

## Admin endpoints (API clients + keys)

> Names depend on your implementation; the included Postman collection assumes:

- `POST /api/admin/clients`
- `GET  /api/admin/clients`
- `POST /api/admin/clients/{clientId}/credentials`
- `GET  /api/admin/clients/{clientId}/credentials`
- `DELETE /api/admin/clients/{clientId}` (cascades credentials)

The credential creation endpoint returns the **raw API key once**. Persist it securely on the client side.

---

## Postman

A ready-to-run Postman collection is included:

- `spring-proxy-toolkit.postman_collection.json`

Import it, set:
- `baseUrl = http://localhost:8080`
  Run:
1) Create client
2) Create credential (stores `apiKeyRaw` into environment)
3) Run demo requests

---

## Database migrations (Flyway)

Migrations live in:
- `src/main/resources/db/migration/`

Typical files:
- `V1__create_audit_call_log.sql`
- `V2__create_idempotency_record.sql`
- `V3__idempotency_hardening.sql`
- `V4__create_api_client_policy.sql`
- `V5__create_api_client_and_credentials.sql`
- `V6__api_client_credentials_on_delete_cascade.sql`

---

## Troubleshooting

### 1) Cache error: “expireAfterWrite was already set”
Cause: reusing a mutable Caffeine builder across caches.  
Fix: `TtlCaffeineCacheManager` must create a **fresh builder per cache** (use `Supplier<Caffeine<..>>`).

### 2) Idempotency always returns different `paymentId`
Most common causes:
- You are not sending `X-Idempotency-Key`
- Filter not registered (`IdempotencyKeyFilter` must be `@Component`)
- Interceptor mistakenly uses correlation id instead of idempotency key  
  It must read: `MDC.get(IdempotencyKeyFilter.MDC_KEY)`

### 3) Flyway fails: “Unsupported Database: PostgreSQL 17.x”
- Add: `implementation 'org.flywaydb:flyway-database-postgresql'`
- Pin docker image to `postgres:16.x`

### 4) You don’t see stacktraces in console
- Ensure logging console pattern includes exceptions (e.g. `%wEx`)
- Ensure global exception handler logs exceptions (`log.error("Unhandled exception", ex)`)

---

## License
Internal/demo project.

This project is licensed under the [MIT License](LICENSE).

---

## Contact

**Dzmitry Ivaniuta** — [dzmitry.ivaniuta.services@gmail.com](mailto:dzmitry.ivaniuta.services@gmail.com) — [GitHub](https://github.com/DimitryIvaniuta)
