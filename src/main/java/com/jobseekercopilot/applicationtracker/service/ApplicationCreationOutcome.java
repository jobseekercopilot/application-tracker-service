package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;

public record ApplicationCreationOutcome(ApplicationRecord record, boolean created) {
}
