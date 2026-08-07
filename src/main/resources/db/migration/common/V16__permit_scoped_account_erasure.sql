CREATE OR REPLACE FUNCTION reject_application_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND current_setting('jobseekercopilot.account_erasure', TRUE) = 'true' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'application_events are append-only';
END;
$$;
