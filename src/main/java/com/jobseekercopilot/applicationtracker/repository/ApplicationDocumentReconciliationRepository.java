package com.jobseekercopilot.applicationtracker.repository;

import com.jobseekercopilot.applicationtracker.entity.ApplicationDocumentReconciliation;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationDocumentReconciliationRepository
        extends JpaRepository<ApplicationDocumentReconciliation, UUID> {

    Optional<ApplicationDocumentReconciliation> findByApplicationIdAndUserId(
            UUID applicationId, String userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select reconciliation
            from ApplicationDocumentReconciliation reconciliation
            where reconciliation.applicationId = :applicationId
            """)
    Optional<ApplicationDocumentReconciliation> findForUpdateByApplicationId(
            @Param("applicationId") UUID applicationId);

    @Query("""
            select reconciliation
            from ApplicationDocumentReconciliation reconciliation
            order by
                case when reconciliation.checkedAt is null then 0 else 1 end,
                reconciliation.checkedAt asc,
                reconciliation.applicationId asc
            """)
    List<ApplicationDocumentReconciliation> findNextBatch(Pageable pageable);
}
