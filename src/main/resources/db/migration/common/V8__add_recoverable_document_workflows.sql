ALTER TABLE application_records
    ADD COLUMN active_document_workflow_id UUID;

CREATE TABLE application_document_workflows (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    workflow_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    cv_document_id VARCHAR(255),
    cover_letter_document_id VARCHAR(255),
    cv_cleanup_required BOOLEAN NOT NULL,
    cover_letter_cleanup_required BOOLEAN NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    source VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL,
    retryable BOOLEAN NOT NULL,
    last_error_code VARCHAR(64),
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITHOUT TIME ZONE,
    workflow_version BIGINT NOT NULL,
    CONSTRAINT uq_application_document_workflow
        UNIQUE (user_id, application_id, workflow_type),
    CONSTRAINT chk_application_document_workflow_type
        CHECK (workflow_type IN ('GENERATED_WITHDRAWAL')),
    CONSTRAINT chk_application_document_workflow_status
        CHECK (status IN (
            'PENDING',
            'RUNNING',
            'RECOVERY_REQUIRED',
            'COMPLETED'
        )),
    CONSTRAINT chk_application_document_workflow_actor_type
        CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM')),
    CONSTRAINT chk_application_document_workflow_source
        CHECK (source IN ('USER', 'SERVICE', 'SYSTEM_DATA')),
    CONSTRAINT chk_application_document_workflow_attempts
        CHECK (attempt_count >= 0),
    CONSTRAINT chk_application_document_workflow_completion
        CHECK (
            (status = 'COMPLETED' AND completed_at IS NOT NULL)
            OR (status <> 'COMPLETED' AND completed_at IS NULL)
        )
);

CREATE INDEX idx_application_document_workflow_recovery
    ON application_document_workflows (
        status,
        retryable,
        updated_at
    );

CREATE INDEX idx_application_document_workflow_owner
    ON application_document_workflows (
        user_id,
        application_id,
        created_at
    );
