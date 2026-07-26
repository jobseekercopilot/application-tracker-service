ALTER TABLE application_records
    ADD COLUMN canonical_job_id VARCHAR(255),
    ADD COLUMN provider VARCHAR(255),
    ADD COLUMN external_job_id VARCHAR(255);

UPDATE application_records
SET canonical_job_id = job_id,
    provider = 'LEGACY',
    external_job_id = job_id
WHERE canonical_job_id IS NULL
   OR provider IS NULL
   OR external_job_id IS NULL;

CREATE INDEX idx_application_records_owner_canonical_job
    ON application_records (user_id, canonical_job_id);

CREATE INDEX idx_application_records_provider_external_job
    ON application_records (provider, external_job_id);
