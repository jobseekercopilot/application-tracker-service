CREATE TABLE application_document_reconciliations (
    application_id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL,
    issue_codes VARCHAR(500),
    checked_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_healthy_at TIMESTAMP(6) WITHOUT TIME ZONE,
    last_repaired_at TIMESTAMP(6) WITHOUT TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    repair_count INTEGER NOT NULL DEFAULT 0,
    application_record_version BIGINT,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    reconciliation_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_application_document_reconciliation
        FOREIGN KEY (application_id)
        REFERENCES application_records (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_application_document_reconciliation_status
        CHECK (status IN (
            'PENDING',
            'HEALTHY',
            'REPAIRED',
            'INVALID',
            'UNAVAILABLE'
        )),
    CONSTRAINT chk_application_document_reconciliation_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_application_document_reconciliation_repairs
        CHECK (repair_count >= 0),
    CONSTRAINT chk_application_document_reconciliation_version
        CHECK (reconciliation_version >= 0)
);

CREATE INDEX idx_application_document_reconciliation_scan
    ON application_document_reconciliations (
        checked_at,
        status,
        application_id
    );

INSERT INTO application_document_reconciliations (
    application_id,
    user_id,
    status,
    attempt_count,
    repair_count,
    application_record_version,
    created_at,
    updated_at,
    reconciliation_version
)
SELECT
    id,
    user_id,
    'PENDING',
    0,
    0,
    record_version,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    0
FROM application_records;

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
            'GENERATED_APPLICATION_WITHDRAWN',
            'APPLICATION_DELETED'
        )
    );

ALTER TABLE application_events
    DROP CONSTRAINT chk_application_events_source;

ALTER TABLE application_events
    ADD CONSTRAINT chk_application_events_source CHECK (
        source IN (
            'USER',
            'SERVICE',
            'SYSTEM',
            'SYSTEM_DATA',
            'MIGRATION'
        )
    );
