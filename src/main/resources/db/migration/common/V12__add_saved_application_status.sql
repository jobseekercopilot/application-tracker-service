ALTER TABLE application_records
    DROP CONSTRAINT chk_application_records_status;

ALTER TABLE application_records
    ADD CONSTRAINT chk_application_records_status CHECK (
        status IN (
            'SAVED',
            'DOCUMENTS_GENERATED',
            'APPLIED',
            'INTERVIEW',
            'UNSUCCESSFUL',
            'OFFER',
            'ACCEPTED',
            'REJECTED_BY_USER',
            'WITHDRAWN'
        )
    );

ALTER TABLE application_records
    DROP CONSTRAINT chk_application_records_provenance_status;

ALTER TABLE application_records
    ADD CONSTRAINT chk_application_records_provenance_status CHECK (
        status <> 'DOCUMENTS_GENERATED'
        OR (
            cv_document_id IS NOT NULL
            AND cover_letter_document_id IS NOT NULL
        )
    );

ALTER TABLE application_events
    DROP CONSTRAINT chk_application_events_from_status;

ALTER TABLE application_events
    ADD CONSTRAINT chk_application_events_from_status CHECK (
        from_status IS NULL
        OR from_status IN (
            'SAVED',
            'DOCUMENTS_GENERATED',
            'APPLIED',
            'INTERVIEW',
            'UNSUCCESSFUL',
            'OFFER',
            'ACCEPTED',
            'REJECTED_BY_USER',
            'WITHDRAWN'
        )
    );

ALTER TABLE application_events
    DROP CONSTRAINT chk_application_events_to_status;

ALTER TABLE application_events
    ADD CONSTRAINT chk_application_events_to_status CHECK (
        to_status IN (
            'SAVED',
            'DOCUMENTS_GENERATED',
            'APPLIED',
            'INTERVIEW',
            'UNSUCCESSFUL',
            'OFFER',
            'ACCEPTED',
            'REJECTED_BY_USER',
            'WITHDRAWN'
        )
    );
