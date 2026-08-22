package com.jobseekercopilot.applicationtracker.systemdata;

import java.util.Map;
import java.util.TreeMap;

public record OwnerRuntimeApplicationSummary(
        int applications,
        int commands,
        int workflows,
        int reconciliations,
        int events,
        int documentAvailabilityProjections,
        Map<String, Long> byStatus) {

    public OwnerRuntimeApplicationSummary {
        byStatus = Map.copyOf(new TreeMap<>(byStatus));
    }

    public int total() {
        return applications
                + commands
                + workflows
                + reconciliations
                + events
                + documentAvailabilityProjections;
    }

    public Map<String, Object> details(String scenarioId, String identityKey) {
        return Map.ofEntries(
                Map.entry("scenarioId", scenarioId),
                Map.entry("identityKey", identityKey),
                Map.entry("applications", applications),
                Map.entry("commands", commands),
                Map.entry("workflows", workflows),
                Map.entry("reconciliations", reconciliations),
                Map.entry("events", events),
                Map.entry(
                        "documentAvailabilityProjections",
                        documentAvailabilityProjections),
                Map.entry("byStatus", byStatus));
    }
}
