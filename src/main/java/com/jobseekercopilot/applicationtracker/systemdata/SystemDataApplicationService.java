package com.jobseekercopilot.applicationtracker.systemdata;

import com.jobseekercopilot.applicationtracker.entity.ApplicationRecord;
import com.jobseekercopilot.applicationtracker.entity.ApplicationStatus;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import com.jobseekercopilot.applicationtracker.repository.ApplicationRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SystemDataApplicationService {
    private static final Set<ApplicationStatus> STATUSES_REQUIRING_APPLIED_AT = EnumSet.of(
            ApplicationStatus.APPLIED,
            ApplicationStatus.INTERVIEW,
            ApplicationStatus.UNSUCCESSFUL,
            ApplicationStatus.OFFER,
            ApplicationStatus.ACCEPTED,
            ApplicationStatus.REJECTED_BY_USER);

    private final ApplicationRecordRepository repository;

    public SystemDataApplicationService(ApplicationRecordRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public int seed(SystemDataApplicationSeedRequest request) {
        validate(request.applications());

        String ownerId = request.userId().toString();
        String scenarioId = request.scenarioId();
        List<ApplicationRecord> current = repository.findByUserIdAndFixtureScenarioId(ownerId, scenarioId);
        Map<UUID, ApplicationRecord> currentById = current.stream()
                .collect(Collectors.toMap(ApplicationRecord::getId, Function.identity()));
        Set<UUID> desiredIds = request.applications().stream()
                .map(SystemDataApplicationSeedRecord::id)
                .collect(Collectors.toSet());

        for (ApplicationRecord collision : repository.findAllById(desiredIds)) {
            if (!ownerId.equals(collision.getUserId())
                    || !scenarioId.equals(collision.getFixtureScenarioId())) {
                throw new InvalidRequestException(
                        "A fixture record identifier is already owned by another record boundary.");
            }
        }

        List<ApplicationRecord> obsolete = current.stream()
                .filter(record -> !desiredIds.contains(record.getId()))
                .toList();
        repository.deleteAll(obsolete);

        List<ApplicationRecord> desired = request.applications().stream()
                .map(seed -> mapRecord(
                        currentById.getOrDefault(seed.id(), new ApplicationRecord()),
                        ownerId,
                        scenarioId,
                        seed))
                .toList();
        repository.saveAll(desired);
        return desired.size();
    }

    @Transactional
    public int reset(UUID userId, String scenarioId) {
        List<ApplicationRecord> records =
                repository.findByUserIdAndFixtureScenarioId(userId.toString(), scenarioId);
        repository.deleteAll(records);
        return records.size();
    }

    @Transactional(readOnly = true)
    public ScenarioApplicationSummary verify(UUID userId, String scenarioId) {
        List<ApplicationRecord> records =
                repository.findByUserIdAndFixtureScenarioId(userId.toString(), scenarioId);
        Map<String, Long> byStatus = records.stream()
                .collect(Collectors.groupingBy(
                        record -> record.getStatus().name(),
                        java.util.TreeMap::new,
                        Collectors.counting()));
        return new ScenarioApplicationSummary(records.size(), byStatus);
    }

    private void validate(List<SystemDataApplicationSeedRecord> records) {
        Set<UUID> identifiers = new HashSet<>();
        for (SystemDataApplicationSeedRecord record : records) {
            if (!identifiers.add(record.id())) {
                throw new InvalidRequestException("Fixture record identifiers must be unique.");
            }
            if (record.updatedAt().isBefore(record.createdAt())) {
                throw new InvalidRequestException(
                        "Fixture updatedAt must not be before createdAt.");
            }
            validateAppliedAt(record);
        }
    }

    private void validateAppliedAt(SystemDataApplicationSeedRecord record) {
        LocalDateTime appliedAt = record.appliedAt();
        if (STATUSES_REQUIRING_APPLIED_AT.contains(record.status()) && appliedAt == null) {
            throw new InvalidRequestException(
                    "The fixture status requires an appliedAt timestamp.");
        }
        if (record.status() == ApplicationStatus.DOCUMENTS_GENERATED && appliedAt != null) {
            throw new InvalidRequestException(
                    "DOCUMENTS_GENERATED fixtures cannot have an appliedAt timestamp.");
        }
        if (appliedAt != null
                && (appliedAt.isBefore(record.createdAt())
                || appliedAt.isAfter(record.updatedAt()))) {
            throw new InvalidRequestException(
                    "Fixture appliedAt must be within the record timestamp range.");
        }
    }

    private ApplicationRecord mapRecord(
            ApplicationRecord target,
            String ownerId,
            String scenarioId,
            SystemDataApplicationSeedRecord source) {
        target.setId(source.id());
        target.setUserId(ownerId);
        target.setFixtureScenarioId(scenarioId);
        target.setJobId(source.jobId());
        target.setCanonicalJobId(normaliseOptional(source.canonicalJobId()));
        target.setProvider(normaliseOptional(source.provider()));
        target.setExternalJobId(normaliseOptional(source.externalJobId()));
        target.setJobTitle(source.jobTitle());
        target.setCompanyName(source.companyName());
        target.setLocation(normaliseOptional(source.location()));
        target.setCvDocumentId(source.cvDocumentId().toString());
        target.setCoverLetterDocumentId(source.coverLetterDocumentId().toString());
        target.setStatus(source.status());
        target.setCreatedAt(source.createdAt());
        target.setUpdatedAt(source.updatedAt());
        target.setAppliedAt(source.appliedAt());
        return target;
    }

    private String normaliseOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record ScenarioApplicationSummary(int count, Map<String, Long> byStatus) {
        public ScenarioApplicationSummary {
            byStatus = Collections.unmodifiableMap(new TreeMap<>(byStatus));
        }
    }
}
