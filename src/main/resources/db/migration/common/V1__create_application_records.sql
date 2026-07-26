CREATE TABLE application_records (
    id UUID PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    job_id VARCHAR(255) NOT NULL,
    job_title VARCHAR(300) NOT NULL,
    company_name VARCHAR(300) NOT NULL,
    location VARCHAR(300),
    cv_document_id VARCHAR(255) NOT NULL,
    cover_letter_document_id VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    applied_at TIMESTAMP(6),
    CONSTRAINT chk_application_records_status CHECK (
        status IN (
            'DOCUMENTS_GENERATED',
            'APPLIED',
            'INTERVIEW',
            'UNSUCCESSFUL',
            'OFFER',
            'ACCEPTED',
            'REJECTED_BY_USER',
            'WITHDRAWN'
        )
    )
);

CREATE INDEX idx_application_records_owner_updated
    ON application_records (user_id, updated_at DESC, id);

CREATE INDEX idx_application_records_owner_job
    ON application_records (user_id, job_id);

CREATE INDEX idx_application_records_owner_cv_document
    ON application_records (user_id, cv_document_id);

CREATE INDEX idx_application_records_owner_cover_letter_document
    ON application_records (user_id, cover_letter_document_id);
