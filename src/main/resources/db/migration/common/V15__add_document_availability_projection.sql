CREATE TABLE document_availability_projections (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    document_id UUID NOT NULL,
    availability VARCHAR(16) NOT NULL,
    unavailable_reason VARCHAR(64),
    unavailable_at TIMESTAMP(6) WITHOUT TIME ZONE,
    lifecycle_occurred_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT uq_document_availability_owner_document
        UNIQUE (owner_id, document_id),
    CONSTRAINT ck_document_availability_state
        CHECK (availability IN ('AVAILABLE', 'ARCHIVED', 'DELETED', 'PURGED')),
    CONSTRAINT ck_document_availability_reason CHECK (
        (availability = 'AVAILABLE'
            AND unavailable_reason IS NULL
            AND unavailable_at IS NULL)
        OR (availability <> 'AVAILABLE'
            AND unavailable_reason IS NOT NULL
            AND unavailable_at IS NOT NULL)
    )
);

CREATE INDEX ix_document_availability_owner
    ON document_availability_projections (owner_id, document_id);
