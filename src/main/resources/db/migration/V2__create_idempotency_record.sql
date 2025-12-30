create table if not exists idempotency_record (
    id bigserial primary key,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    idempotency_key varchar(128) not null,
    method_key varchar(1024) not null, -- class#method(sig)

    request_hash varchar(128) not null,
    status varchar(16) not null, -- PENDING / COMPLETED / FAILED

    response_json jsonb,
    error_message text
);

create unique index if not exists ux_idempotency_key_method
    on idempotency_record (idempotency_key, method_key);

create index if not exists ix_idempotency_created_at
    on idempotency_record (created_at desc);
