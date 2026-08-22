CREATE TABLE application_events (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    fixture_scenario_id VARCHAR(64),
    event_type VARCHAR(48) NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_type VARCHAR(16) NOT NULL,
    actor_id VARCHAR(255) NOT NULL,
    source VARCHAR(24) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    reason VARCHAR(500),
    record_version BIGINT NOT NULL,
    CONSTRAINT chk_application_events_type CHECK (
        event_type IN (
            'LEGACY_SNAPSHOT',
            'APPLICATION_CREATED',
            'STATUS_CHANGED',
            'DOCUMENT_REFERENCE_CHANGED',
            'GENERATED_APPLICATION_WITHDRAWN',
            'APPLICATION_DELETED'
        )
    ),
    CONSTRAINT chk_application_events_from_status CHECK (
        from_status IS NULL
        OR from_status IN (
            'DOCUMENTS_GENERATED',
            'APPLIED',
            'INTERVIEW',
            'UNSUCCESSFUL',
            'OFFER',
            'ACCEPTED',
            'REJECTED_BY_USER',
            'WITHDRAWN'
        )
    ),
    CONSTRAINT chk_application_events_to_status CHECK (
        to_status IN (
            'DOCUMENTS_GENERATED',
            'APPLIED',
            'INTERVIEW',
            'UNSUCCESSFUL',
            'OFFER',
            'ACCEPTED',
            'REJECTED_BY_USER',
            'WITHDRAWN'
        )
    ),
    CONSTRAINT chk_application_events_actor_type CHECK (
        actor_type IN ('USER', 'SERVICE', 'SYSTEM')
    ),
    CONSTRAINT chk_application_events_source CHECK (
        source IN ('USER', 'SERVICE', 'SYSTEM_DATA', 'MIGRATION')
    ),
    CONSTRAINT chk_application_events_record_version CHECK (
        record_version >= 0
    ),
    CONSTRAINT chk_application_events_time CHECK (
        event_type = 'LEGACY_SNAPSHOT'
        OR occurred_at <= recorded_at + INTERVAL '5 minutes'
    ),
    CONSTRAINT uq_application_events_record_version UNIQUE (
        application_id,
        record_version
    )
);

CREATE INDEX idx_application_events_owner_application_time
    ON application_events (
        user_id,
        application_id,
        occurred_at,
        recorded_at,
        id
    );

CREATE INDEX idx_application_events_owner_record_version
    ON application_events (
        user_id,
        application_id,
        record_version DESC
    );

INSERT INTO application_events (
    id,
    application_id,
    user_id,
    fixture_scenario_id,
    event_type,
    from_status,
    to_status,
    actor_type,
    actor_id,
    source,
    occurred_at,
    recorded_at,
    reason,
    record_version
)
SELECT
    id,
    id,
    user_id,
    fixture_scenario_id,
    'LEGACY_SNAPSHOT',
    NULL,
    status,
    'SYSTEM',
    'migration-v7',
    'MIGRATION',
    updated_at AT TIME ZONE 'UTC',
    CURRENT_TIMESTAMP,
    'Lifecycle history before event tracking is unavailable.',
    record_version
FROM application_records;

CREATE FUNCTION reject_application_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'application_events are append-only';
END;
$$;

CREATE TRIGGER application_events_append_only
BEFORE UPDATE OR DELETE ON application_events
FOR EACH ROW
EXECUTE FUNCTION reject_application_event_mutation();
