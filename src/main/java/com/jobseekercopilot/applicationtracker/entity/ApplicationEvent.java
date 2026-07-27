package com.jobseekercopilot.applicationtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

@Entity
@Table(name = "application_events")
@Immutable
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ApplicationEvent {

    @Id
    @Builder.Default
    @Column(updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, updatable = false)
    private UUID applicationId;

    @Column(nullable = false, updatable = false)
    private String userId;

    @Column(length = 64, updatable = false)
    private String fixtureScenarioId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48, updatable = false)
    private ApplicationEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, updatable = false)
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private ApplicationStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private ApplicationActorType actorType;

    @Column(nullable = false, length = 255, updatable = false)
    private String actorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, updatable = false)
    private ApplicationEventSource source;

    @Column(nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(length = 500, updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false)
    private long recordVersion;
}
