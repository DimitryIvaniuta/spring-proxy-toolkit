-- Ensure FK from api_client_credential(api_client_id) -> api_client(id) has ON DELETE CASCADE

ALTER TABLE api_client_credential
DROP CONSTRAINT IF EXISTS fk_api_client_credential_client;

ALTER TABLE api_client_credential
    ADD CONSTRAINT fk_api_client_credential_client
        FOREIGN KEY (api_client_id)
            REFERENCES api_client(id)
            ON DELETE CASCADE;
