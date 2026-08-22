ALTER TABLE application_records
    ADD COLUMN cv_document_family_id VARCHAR(255),
    ADD COLUMN cv_document_version INTEGER,
    ADD COLUMN cv_document_content_sha256 VARCHAR(64),
    ADD COLUMN cover_letter_document_family_id VARCHAR(255),
    ADD COLUMN cover_letter_document_version INTEGER,
    ADD COLUMN cover_letter_document_content_sha256 VARCHAR(64),
    ADD COLUMN application_used_cv_document_id VARCHAR(255),
    ADD COLUMN application_used_cv_document_family_id VARCHAR(255),
    ADD COLUMN application_used_cv_document_version INTEGER,
    ADD COLUMN application_used_cv_document_content_sha256 VARCHAR(64),
    ADD COLUMN application_used_cover_letter_document_id VARCHAR(255),
    ADD COLUMN application_used_cover_letter_document_family_id VARCHAR(255),
    ADD COLUMN application_used_cover_letter_document_version INTEGER,
    ADD COLUMN application_used_cover_letter_document_content_sha256 VARCHAR(64),
    ADD COLUMN application_used_at TIMESTAMP(6);

CREATE INDEX idx_application_records_owner_used_cv_document
    ON application_records (user_id, application_used_cv_document_id)
    WHERE application_used_cv_document_id IS NOT NULL;

CREATE INDEX idx_application_records_owner_used_cover_letter_document
    ON application_records (user_id, application_used_cover_letter_document_id)
    WHERE application_used_cover_letter_document_id IS NOT NULL;
