ALTER TABLE application_records
    ADD COLUMN record_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE application_records
    ADD CONSTRAINT chk_application_records_version
    CHECK (record_version >= 0);
