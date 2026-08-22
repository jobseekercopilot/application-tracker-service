package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationEventResponse;
import com.jobseekercopilot.applicationtracker.dto.ApplicationHistoryResponse;
import com.jobseekercopilot.applicationtracker.entity.ApplicationEvent;
import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.exception.ResourceNotFoundException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationEventRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationHistoryService {

    private final ApplicationRecordRepository applicationRepository;
    private final ApplicationEventRepository eventRepository;

    @Transactional(readOnly = true)
    public ApplicationHistoryResponse getHistory(
            String ownerId, UUID applicationId, int page, int size) {
        ApplicationRecord application =
                applicationRepository.findByIdAndUserId(applicationId, ownerId)
                        .orElseThrow(ResourceNotFoundException::applicationNotFound);
        Page<ApplicationEvent> events =
                eventRepository
                        .findByApplicationIdAndUserIdOrderByOccurredAtAscRecordedAtAscIdAsc(
                                applicationId,
                                ownerId,
                                PageRequest.of(page, size));
        boolean reconciled = eventRepository
                .findFirstByApplicationIdAndUserIdOrderByRecordVersionDescRecordedAtDesc(
                        applicationId, ownerId)
                .map(latest ->
                        latest.getToStatus() == application.getStatus()
                                && latest.getRecordVersion()
                                == application.getVersion())
                .orElse(false);
        return ApplicationHistoryResponse.builder()
                .applicationId(applicationId)
                .currentStatus(application.getStatus())
                .currentVersion(application.getVersion())
                .reconciled(reconciled)
                .events(events.stream().map(this::map).toList())
                .page(events.getNumber())
                .size(events.getSize())
                .totalElements(events.getTotalElements())
                .totalPages(events.getTotalPages())
                .build();
    }

    private ApplicationEventResponse map(ApplicationEvent event) {
        return ApplicationEventResponse.builder()
                .id(event.getId())
                .applicationId(event.getApplicationId())
                .eventType(event.getEventType())
                .fromStatus(event.getFromStatus())
                .toStatus(event.getToStatus())
                .actorType(event.getActorType())
                .actorId(event.getActorId())
                .source(event.getSource())
                .occurredAt(event.getOccurredAt())
                .recordedAt(event.getRecordedAt())
                .reason(event.getReason())
                .recordVersion(event.getRecordVersion())
                .build();
    }
}
