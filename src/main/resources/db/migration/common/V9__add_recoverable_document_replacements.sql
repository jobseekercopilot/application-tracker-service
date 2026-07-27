ALTER TABLE application_document_workflows
    DROP CONSTRAINT uq_application_document_workflow;

ALTER TABLE application_document_workflows
    DROP CONSTRAINT chk_application_document_workflow_type;

ALTER TABLE application_document_workflows
    ADD COLUMN document_type VARCHAR(32);

ALTER TABLE application_document_workflows
    ADD COLUMN source_document_id VARCHAR(255);

ALTER TABLE application_document_workflows
    ADD COLUMN replacement_document_id VARCHAR(255);

ALTER TABLE application_document_workflows
    ADD COLUMN request_sha256 VARCHAR(64);

ALTER TABLE application_document_workflows
    ADD CONSTRAINT chk_application_document_workflow_type
        CHECK (workflow_type IN (
            'GENERATED_WITHDRAWAL',
            'DOCUMENT_REPLACEMENT'
        ));

ALTER TABLE application_document_workflows
    ADD CONSTRAINT chk_application_document_workflow_replacement
        CHECK (
            (
                workflow_type = 'GENERATED_WITHDRAWAL'
                AND document_type IS NULL
                AND source_document_id IS NULL
                AND replacement_document_id IS NULL
                AND request_sha256 IS NULL
            )
            OR
            (
                workflow_type = 'DOCUMENT_REPLACEMENT'
                AND document_type IN ('CV', 'COVER_LETTER')
                AND source_document_id IS NOT NULL
                AND LENGTH(request_sha256) = 64
            )
        );

CREATE INDEX idx_application_document_workflow_replacement
    ON application_document_workflows (
        workflow_type,
        status,
        retryable,
        updated_at
    );
