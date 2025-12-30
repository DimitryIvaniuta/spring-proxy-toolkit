create table if not exists api_client_policy (
    client_key varchar(128) not null,     -- e.g. apiKey:abc, user:john, ip:1.2.3.4
    method_key varchar(1024) not null,    -- e.g. com..Service#create(String)

    enabled boolean not null default true,

    -- rate limiting override (nullable = "use annotation/default")
    rl_permits_per_sec integer,
    rl_burst integer,

    -- retries override
    retry_max_attempts integer,
    retry_backoff_ms integer,

    -- caching override
    cache_ttl_seconds integer,

    -- idempotency override
    idempotency_ttl_seconds integer,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    primary key (client_key, method_key)
);

create index if not exists ix_api_client_policy_method_key
    on api_client_policy (method_key);

create index if not exists ix_api_client_policy_updated_at
    on api_client_policy (updated_at desc);
