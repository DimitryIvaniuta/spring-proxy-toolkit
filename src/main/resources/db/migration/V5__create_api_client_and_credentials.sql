create table if not exists api_client (
    id bigserial primary key,
    client_name varchar(255) not null,
    enabled boolean not null default true,

    -- optional: used for grouping/policies if you want stable identifier (not the api key itself)
    client_code varchar(64) not null unique,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists ix_api_client_enabled on api_client (enabled);

create table if not exists api_client_credential (
    id bigserial primary key,

    api_client_id bigint not null,
    credential_name varchar(255) not null,

    -- store hash only (recommended), NOT plain token
    api_key_hash varchar(128) not null unique,

    enabled boolean not null default true,
    last_used_at timestamptz,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint fk_api_client_credential_client
    foreign key (api_client_id) references api_client(id)
    on delete cascade
);

create index if not exists ix_api_client_credential_client on api_client_credential (api_client_id);
create index if not exists ix_api_client_credential_enabled on api_client_credential (enabled);
