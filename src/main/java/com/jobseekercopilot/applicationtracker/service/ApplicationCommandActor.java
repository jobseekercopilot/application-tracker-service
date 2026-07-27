package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.entity.ApplicationActorType;
import com.jobseekercopilot.applicationtracker.entity.ApplicationEventSource;
import java.util.Objects;

public record ApplicationCommandActor(
        ApplicationActorType actorType,
        String actorId,
        ApplicationEventSource source) {

    public ApplicationCommandActor {
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(source, "source");
        if (actorId == null || actorId.isBlank() || actorId.length() > 255) {
            throw new IllegalArgumentException("actorId must contain 1-255 characters");
        }
        actorId = actorId.trim();
    }

    public static ApplicationCommandActor user(String subject) {
        return new ApplicationCommandActor(
                ApplicationActorType.USER,
                subject,
                ApplicationEventSource.USER);
    }

    public static ApplicationCommandActor service(String serviceIdentity) {
        return new ApplicationCommandActor(
                ApplicationActorType.SERVICE,
                serviceIdentity,
                ApplicationEventSource.SERVICE);
    }

    public static ApplicationCommandActor systemData(String identity) {
        return new ApplicationCommandActor(
                ApplicationActorType.SYSTEM,
                identity,
                ApplicationEventSource.SYSTEM_DATA);
    }
}
