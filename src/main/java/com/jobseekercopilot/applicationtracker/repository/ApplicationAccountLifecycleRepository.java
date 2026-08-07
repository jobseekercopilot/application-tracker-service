package com.jobseekercopilot.applicationtracker.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ApplicationAccountLifecycleRepository {

    private final JdbcTemplate jdbcTemplate;

    public void erase(String ownerId) {
        jdbcTemplate.update(
                "delete from application_document_workflows where user_id = ?", ownerId);
        jdbcTemplate.update(
                "delete from document_availability_projections where owner_id = ?", ownerId);
        jdbcTemplate.queryForObject(
                "select set_config('jobseekercopilot.account_erasure', 'true', true)",
                String.class);
        jdbcTemplate.update("delete from application_events where user_id = ?", ownerId);
        jdbcTemplate.update("delete from application_records where user_id = ?", ownerId);
    }
}
