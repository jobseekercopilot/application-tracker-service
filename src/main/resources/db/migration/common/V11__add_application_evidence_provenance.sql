ALTER TABLE application_records
    ADD COLUMN cv_document_evidence_provenance TEXT;

ALTER TABLE application_records
    ADD COLUMN cv_document_grounding_state VARCHAR(48);

ALTER TABLE application_records
    ADD COLUMN cover_letter_document_evidence_provenance TEXT;

ALTER TABLE application_records
    ADD COLUMN cover_letter_document_grounding_state VARCHAR(48);

ALTER TABLE application_records
    ADD COLUMN application_used_cv_evidence_provenance TEXT;

ALTER TABLE application_records
    ADD COLUMN application_used_cv_grounding_state VARCHAR(48);

ALTER TABLE application_records
    ADD COLUMN application_used_cover_letter_evidence_provenance TEXT;

ALTER TABLE application_records
    ADD COLUMN application_used_cover_letter_grounding_state VARCHAR(48);

ALTER TABLE application_records
    ADD CONSTRAINT chk_application_records_cv_grounding CHECK (
        cv_document_grounding_state IS NULL
        OR cv_document_grounding_state IN (
            'AI_GENERATED_EVIDENCE_VALIDATED',
            'USER_EDITED_REVIEW_REQUIRED',
            'USER_EDITED_REVALIDATED',
            'LEGACY_UNSPECIFIED'
        )
    );

ALTER TABLE application_records
    ADD CONSTRAINT chk_application_records_cover_grounding CHECK (
        cover_letter_document_grounding_state IS NULL
        OR cover_letter_document_grounding_state IN (
            'AI_GENERATED_EVIDENCE_VALIDATED',
            'USER_EDITED_REVIEW_REQUIRED',
            'USER_EDITED_REVALIDATED',
            'LEGACY_UNSPECIFIED'
        )
    );

ALTER TABLE application_records
    ADD CONSTRAINT chk_application_records_used_cv_grounding CHECK (
        application_used_cv_grounding_state IS NULL
        OR application_used_cv_grounding_state IN (
            'AI_GENERATED_EVIDENCE_VALIDATED',
            'USER_EDITED_REVIEW_REQUIRED',
            'USER_EDITED_REVALIDATED',
            'LEGACY_UNSPECIFIED'
        )
    );

ALTER TABLE application_records
    ADD CONSTRAINT chk_application_records_used_cover_grounding CHECK (
        application_used_cover_letter_grounding_state IS NULL
        OR application_used_cover_letter_grounding_state IN (
            'AI_GENERATED_EVIDENCE_VALIDATED',
            'USER_EDITED_REVIEW_REQUIRED',
            'USER_EDITED_REVALIDATED',
            'LEGACY_UNSPECIFIED'
        )
    );
