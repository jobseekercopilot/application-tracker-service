package com.jobseekercopilot.applicationtracker.systemdata;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class OwnerRuntimeApplicationRepository {
    private final JdbcTemplate jdbcTemplate;

    public OwnerRuntimeApplicationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public OwnerRuntimeApplicationSummary summary(String ownerId) {
        return new OwnerRuntimeApplicationSummary(
                count("application_records", "user_id", ownerId),
                count("application_document_selection_commands", "user_id", ownerId)
                        + count("application_applied_commands", "user_id", ownerId),
                count("application_document_workflows", "user_id", ownerId),
                count("application_document_reconciliations", "user_id", ownerId),
                count("application_events", "user_id", ownerId),
                count("document_availability_projections", "owner_id", ownerId),
                byStatus(ownerId));
    }

    public OwnerRuntimeApplicationSummary delete(String ownerId) {
        OwnerRuntimeApplicationSummary before = summary(ownerId);
        jdbcTemplate.update(
                "delete from application_document_workflows where user_id = ?",
                ownerId);
        jdbcTemplate.update(
                "delete from document_availability_projections where owner_id = ?",
                ownerId);
        jdbcTemplate.update(
                "delete from application_document_selection_commands where user_id = ?",
                ownerId);
        jdbcTemplate.update(
                "delete from application_applied_commands where user_id = ?",
                ownerId);
        jdbcTemplate.update(
                "delete from application_document_reconciliations where user_id = ?",
                ownerId);
        allowImmutableEventDeletion(ownerId);
        jdbcTemplate.update("delete from application_events where user_id = ?", ownerId);
        jdbcTemplate.update("delete from application_records where user_id = ?", ownerId);
        return before;
    }

    private int count(String table, String ownerColumn, String ownerId) {
        Integer value = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + ownerColumn + " = ?",
                Integer.class,
                ownerId);
        return value == null ? 0 : value;
    }

    private java.util.Map<String, Long> byStatus(String ownerId) {
        java.util.Map<String, Long> statuses = new java.util.TreeMap<>();
        jdbcTemplate.query(
                "select status, count(*) from application_records where user_id = ? group by status",
                (RowCallbackHandler) result -> statuses.put(
                        result.getString(1), result.getLong(2)),
                ownerId);
        return statuses;
    }

    private void allowImmutableEventDeletion(String ownerId) {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            String product = connection.getMetaData().getDatabaseProductName();
            if ("H2".equals(product)) {
                return null;
            }
            if (!"PostgreSQL".equals(product)) {
                throw new SQLException(
                        "Runtime owner cleanup is unsupported for database product "
                                + product);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "select set_config('jobseekercopilot.account_erasure', ?, true)")) {
                statement.setString(1, "true");
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !"true".equals(result.getString(1))) {
                        throw new SQLException(
                                "Could not establish transaction-local event deletion guard for owner "
                                        + ownerId);
                    }
                }
            }
            return null;
        });
    }
}
