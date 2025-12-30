alter table idempotency_record
    add column if not exists expires_at timestamptz,
    add column if not exists locked_at timestamptz,
    add column if not exists locked_by varchar(128);

create index if not exists ix_idempotency_expires_at
    on idempotency_record (expires_at);

