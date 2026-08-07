package com.jobseekercopilot.applicationtracker.repository;

import com.jobseekercopilot.applicationtracker.entity.ApplicationEvent;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface ApplicationEventRepository
        extends Repository<ApplicationEvent, UUID> {

    Page<ApplicationEvent> findByApplicationIdAndUserIdOrderByOccurredAtAscRecordedAtAscIdAsc(
            UUID applicationId, String userId, Pageable pageable);

    Optional<ApplicationEvent>
            findFirstByApplicationIdAndUserIdOrderByRecordVersionDescRecordedAtDesc(
                    UUID applicationId, String userId);

    long countByApplicationIdAndUserId(UUID applicationId, String userId);

    List<ApplicationEvent> findByUserIdOrderByOccurredAtAscRecordedAtAscIdAsc(String userId);
}
