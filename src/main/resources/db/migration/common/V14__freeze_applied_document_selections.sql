ALTER TABLE application_records
    ADD COLUMN application_used_cv_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

ALTER TABLE application_records
    ADD COLUMN application_used_cover_letter_state VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN';

UPDATE application_records
SET application_used_cv_state = 'SELECTED'
WHERE application_used_cv_document_id IS NOT NULL;

UPDATE application_records
SET application_used_cover_letter_state = 'SELECTED'
WHERE application_used_cover_letter_document_id IS NOT NULL;

ALTER TABLE application_records
    ADD CONSTRAINT chk_application_records_used_cv_state CHECK (
        application_used_cv_state IN ('UNKNOWN', 'SELECTED', 'OMITTED')
        AND (application_used_cv_state <> 'SELECTED'
             OR application_used_cv_document_id IS NOT NULL)
        AND (application_used_cv_state <> 'OMITTED'
             OR application_used_cv_document_id IS NULL)
    );

ALTER TABLE application_records
    ADD CONSTRAINT chk_application_records_used_cover_state CHECK (
        application_used_cover_letter_state IN ('UNKNOWN', 'SELECTED', 'OMITTED')
        AND (application_used_cover_letter_state <> 'SELECTED'
             OR application_used_cover_letter_document_id IS NOT NULL)
        AND (application_used_cover_letter_state <> 'OMITTED'
             OR application_used_cover_letter_document_id IS NULL)
    );

CREATE TABLE application_applied_commands (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    application_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    outcome_response_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_application_applied_command
        FOREIGN KEY (application_id)
        REFERENCES application_records (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_application_applied_fingerprint CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT uq_application_applied_idempotency
        UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_application_applied_command_application
    ON application_applied_commands (application_id, created_at);

ALTER TABLE application_events
    DROP CONSTRAINT chk_application_events_type;

ALTER TABLE application_events
    ADD CONSTRAINT chk_application_events_type CHECK (
        event_type IN (
            'LEGACY_SNAPSHOT',
            'APPLICATION_CREATED',
            'STATUS_CHANGED',
            'DOCUMENT_REFERENCE_CHANGED',
            'DOCUMENT_REFERENCES_RECONCILED',
            'APPLICATION_DOCUMENTS_FROZEN',
            'GENERATED_APPLICATION_WITHDRAWN',
            'APPLICATION_DELETED'
        )
    );

ALTER TABLE application_events
    DROP CONSTRAINT uq_application_events_record_version;

ALTER TABLE application_events
    ADD CONSTRAINT uq_application_events_record_version_type UNIQUE (
        application_id,
        record_version,
        event_type
    );
