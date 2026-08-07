CREATE TABLE application_document_selection_commands (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    application_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    outcome_response_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_application_document_selection_command
        FOREIGN KEY (application_id)
        REFERENCES application_records (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_application_document_selection_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT uq_application_document_selection_idempotency
        UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_application_document_selection_application
    ON application_document_selection_commands (application_id, created_at);

-- Selection commands may explicitly omit either optional slot while the
-- application is still editable. Creation and lifecycle services continue to
-- enforce their own generated-document prerequisites.
ALTER TABLE application_records
    DROP CONSTRAINT chk_application_records_provenance_status;

ALTER TABLE application_records
    DROP CONSTRAINT chk_application_records_generated_documents;
