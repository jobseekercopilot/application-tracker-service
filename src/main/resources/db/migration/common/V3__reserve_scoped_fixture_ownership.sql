ALTER TABLE application_records
    ADD COLUMN fixture_scenario_id VARCHAR(64);

CREATE INDEX idx_application_records_owner_fixture_scenario
    ON application_records (user_id, fixture_scenario_id)
    WHERE fixture_scenario_id IS NOT NULL;
