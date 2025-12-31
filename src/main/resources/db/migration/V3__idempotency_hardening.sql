-- Aligns schema with IdempotencyRecord entity:
--   expires_at, locked_at, locked_by, created_at, updated_at
-- plus indexes and the required unique constraint on (idempotency_key, method_key).

ALTER TABLE idempotency_record
    ADD COLUMN IF NOT EXISTS expires_at timestamptz,
    ADD COLUMN IF NOT EXISTS locked_at timestamptz,
    ADD COLUMN IF NOT EXISTS locked_by varchar(128),
    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

-- Ensure NOT NULL constraints align with entity
ALTER TABLE idempotency_record
    ALTER COLUMN idempotency_key SET NOT NULL,
    ALTER COLUMN method_key SET NOT NULL,
    ALTER COLUMN request_hash SET NOT NULL,
    ALTER COLUMN status SET NOT NULL;

-- Unique key used by code (lookup + locking semantics)
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'uk_idempotency_record_key_method'
  ) THEN
ALTER TABLE idempotency_record
    ADD CONSTRAINT uk_idempotency_record_key_method UNIQUE (idempotency_key, method_key);
END IF;
END$$;

-- Indexes for fast lookups and TTL cleanup
CREATE INDEX IF NOT EXISTS ix_idempotency_record_key_method
    ON idempotency_record (idempotency_key, method_key);

CREATE INDEX IF NOT EXISTS ix_idempotency_record_expires_at
    ON idempotency_record (expires_at);

CREATE INDEX IF NOT EXISTS ix_idempotency_record_updated_at
    ON idempotency_record (updated_at DESC);