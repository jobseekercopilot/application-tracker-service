ALTER TABLE application_records
    ALTER COLUMN cv_document_id DROP NOT NULL,
    ALTER COLUMN cover_letter_document_id DROP NOT NULL,
    ADD COLUMN provenance VARCHAR(32) NOT NULL DEFAULT 'GENERATED',
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD COLUMN create_request_fingerprint VARCHAR(64);

ALTER TABLE application_records
    ALTER COLUMN provenance DROP DEFAULT,
    ADD CONSTRAINT chk_application_records_provenance CHECK (
        provenance IN ('GENERATED', 'MANUAL', 'EXTERNAL')
    ),
    ADD CONSTRAINT chk_application_records_creation_identity CHECK (
        (idempotency_key IS NULL AND create_request_fingerprint IS NULL)
        OR
        (idempotency_key IS NOT NULL AND create_request_fingerprint IS NOT NULL)
    ),
    ADD CONSTRAINT chk_application_records_create_fingerprint CHECK (
        create_request_fingerprint IS NULL
        OR create_request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT chk_application_records_provenance_status CHECK (
        provenance = 'GENERATED'
        OR status <> 'DOCUMENTS_GENERATED'
    ),
    ADD CONSTRAINT chk_application_records_generated_documents CHECK (
        provenance <> 'GENERATED'
        OR (
            cv_document_id IS NOT NULL
            AND cover_letter_document_id IS NOT NULL
        )
    );

CREATE UNIQUE INDEX uq_application_records_owner_idempotency_key
    ON application_records (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_application_records_owner_canonical_job
    ON application_records (user_id, canonical_job_id)
    WHERE canonical_job_id IS NOT NULL
      AND fixture_scenario_id IS NULL;
