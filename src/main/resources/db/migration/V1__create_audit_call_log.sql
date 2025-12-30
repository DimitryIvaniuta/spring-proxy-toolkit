create table if not exists audit_call_log (
    id bigserial primary key,
    created_at timestamptz not null default now(),

    correlation_id varchar(64),
    trace_id varchar(128),

    bean_name varchar(255) not null,
    target_class varchar(512) not null,
    method_signature varchar(1024) not null,

    args jsonb,
    result jsonb,

    status varchar(32) not null,
    duration_ms bigint not null,

    error_message text,
    error_stack text
);

create index if not exists ix_audit_call_log_created_at on audit_call_log (created_at desc);
create index if not exists ix_audit_call_log_corr on audit_call_log (correlation_id);
