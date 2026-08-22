ALTER TABLE application_records
    ADD COLUMN cv_document_source_type VARCHAR(16),
    ADD COLUMN cv_document_original_content_sha256 VARCHAR(64),
    ADD COLUMN cv_document_selected_at TIMESTAMP,
    ADD COLUMN cover_letter_document_source_type VARCHAR(16),
    ADD COLUMN cover_letter_document_original_content_sha256 VARCHAR(64),
    ADD COLUMN cover_letter_document_selected_at TIMESTAMP,
    ADD COLUMN application_used_cv_document_source_type VARCHAR(16),
    ADD COLUMN application_used_cv_document_original_content_sha256 VARCHAR(64),
    ADD COLUMN application_used_cv_document_selected_at TIMESTAMP,
    ADD COLUMN application_used_cover_letter_document_source_type VARCHAR(16),
    ADD COLUMN application_used_cover_letter_document_original_content_sha256 VARCHAR(64),
    ADD COLUMN application_used_cover_letter_document_selected_at TIMESTAMP;

UPDATE application_records
SET cv_document_selected_at = COALESCE(updated_at, created_at)
WHERE cv_document_id IS NOT NULL;

UPDATE application_records
SET cover_letter_document_selected_at = COALESCE(updated_at, created_at)
WHERE cover_letter_document_id IS NOT NULL;

UPDATE application_records
SET application_used_cv_document_selected_at =
        COALESCE(application_used_at, applied_at, updated_at, created_at)
WHERE application_used_cv_document_id IS NOT NULL;

UPDATE application_records
SET application_used_cover_letter_document_selected_at =
        COALESCE(application_used_at, applied_at, updated_at, created_at)
WHERE application_used_cover_letter_document_id IS NOT NULL;

ALTER TABLE application_records
    ADD CONSTRAINT chk_application_records_cv_source CHECK (
        cv_document_source_type IS NULL
        OR cv_document_source_type IN ('GENERATED', 'UPLOADED')
    ),
    ADD CONSTRAINT chk_application_records_cover_source CHECK (
        cover_letter_document_source_type IS NULL
        OR cover_letter_document_source_type IN ('GENERATED', 'UPLOADED')
    ),
    ADD CONSTRAINT chk_application_records_used_cv_source CHECK (
        application_used_cv_document_source_type IS NULL
        OR application_used_cv_document_source_type IN ('GENERATED', 'UPLOADED')
    ),
    ADD CONSTRAINT chk_application_records_used_cover_source CHECK (
        application_used_cover_letter_document_source_type IS NULL
        OR application_used_cover_letter_document_source_type IN ('GENERATED', 'UPLOADED')
    ),
    ADD CONSTRAINT chk_application_records_cv_original_sha CHECK (
        cv_document_original_content_sha256 IS NULL
        OR cv_document_original_content_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT chk_application_records_cover_original_sha CHECK (
        cover_letter_document_original_content_sha256 IS NULL
        OR cover_letter_document_original_content_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT chk_application_records_used_cv_original_sha CHECK (
        application_used_cv_document_original_content_sha256 IS NULL
        OR application_used_cv_document_original_content_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT chk_application_records_used_cover_original_sha CHECK (
        application_used_cover_letter_document_original_content_sha256 IS NULL
        OR application_used_cover_letter_document_original_content_sha256 ~ '^[0-9a-f]{64}$'
    ),
    ADD CONSTRAINT chk_application_records_cv_uploaded_original CHECK (
        cv_document_source_type <> 'UPLOADED'
        OR cv_document_original_content_sha256 IS NOT NULL
    ),
    ADD CONSTRAINT chk_application_records_cover_uploaded_original CHECK (
        cover_letter_document_source_type <> 'UPLOADED'
        OR cover_letter_document_original_content_sha256 IS NOT NULL
    ),
    ADD CONSTRAINT chk_application_records_used_cv_uploaded_original CHECK (
        application_used_cv_document_source_type <> 'UPLOADED'
        OR application_used_cv_document_original_content_sha256 IS NOT NULL
    ),
    ADD CONSTRAINT chk_application_records_used_cover_uploaded_original CHECK (
        application_used_cover_letter_document_source_type <> 'UPLOADED'
        OR application_used_cover_letter_document_original_content_sha256 IS NOT NULL
    );
