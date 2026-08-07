package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.ApplicationEventResponse;
import com.jobseekercopilot.applicationtracker.dto.ApplicationPersonalDataExport;
import com.jobseekercopilot.applicationtracker.entity.ApplicationEvent;
import com.jobseekercopilot.applicationtracker.repository.ApplicationAccountLifecycleRepository;
import com.jobseekercopilot.applicationtracker.repository.ApplicationEventRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ApplicationAccountLifecycleService {

    private final ApplicationRecordService applicationService;
    private final ApplicationEventRepository eventRepository;
    private final ApplicationAccountLifecycleRepository lifecycleRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ApplicationPersonalDataExport export(String ownerId) {
        return new ApplicationPersonalDataExport(
                "application-personal-data.v1",
                clock.instant(),
                applicationService.getApplicationsForUser(ownerId),
                eventRepository.findByUserIdOrderByOccurredAtAscRecordedAtAscIdAsc(ownerId)
                        .stream()
                        .map(this::eventResponse)
                        .toList());
    }

    @Transactional
    public void erase(String ownerId) {
        lifecycleRepository.erase(ownerId);
    }

    private ApplicationEventResponse eventResponse(ApplicationEvent event) {
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
