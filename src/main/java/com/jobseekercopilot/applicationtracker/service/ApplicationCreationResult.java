package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;

public record ApplicationCreationResult(
        ApplicationRecordResponse application, boolean created) {
}
