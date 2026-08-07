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
            'APPLICATION_DOCUMENT_SELECTED',
            'APPLICATION_DOCUMENT_SELECTION_CHANGED',
            'APPLICATION_DOCUMENTS_FROZEN',
            'GENERATED_APPLICATION_WITHDRAWN',
            'APPLICATION_DELETED'
        )
    );
