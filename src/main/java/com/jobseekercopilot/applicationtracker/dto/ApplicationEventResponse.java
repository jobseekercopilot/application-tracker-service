package com.jobseekercopilot.applicationtracker.dto;

import com.jobseekercopilot.applicationtracker.entity.ApplicationActorType;
import com.jobseekercopilot.applicationtracker.entity.ApplicationEventSource;
import com.jobseekercopilot.applicationtracker.entity.ApplicationEventType;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
@Schema(description = "Immutable application activity event")
public record ApplicationEventResponse(
        UUID id,
        UUID applicationId,
        ApplicationEventType eventType,
        ApplicationStatus fromStatus,
        ApplicationStatus toStatus,
        ApplicationActorType actorType,
        String actorId,
        ApplicationEventSource source,
        Instant occurredAt,
        Instant recordedAt,
        String reason,
        long recordVersion) {
}
