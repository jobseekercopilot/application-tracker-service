package com.jobseekercopilot.applicationtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobseekercopilot.applicationtracker.dto.ApplicationRecordResponse;
import com.jobseekercopilot.applicationtracker.dto.UpdateStatusRequest;
import com.jobseekercopilot.applicationtracker.exception.InvalidRequestException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApplicationAppliedFreezeServiceTest {
    @Mock private ApplicationAppliedFreezeTransaction transaction;

    @Test
    void rejectsMissingConcurrencyOrUnsafeIdempotencyBeforeTransaction() {
        ApplicationAppliedFreezeService service =
                new ApplicationAppliedFreezeService(transaction);
        UUID applicationId = UUID.randomUUID();

        assertThatThrownBy(() -> service.apply(
                        "alice",
                        applicationId,
                        "apply-1",
                        UpdateStatusRequest.builder().status("APPLIED").build(),
                        ApplicationCommandActor.user("alice")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("expectedVersion");
        assertThatThrownBy(() -> service.apply(
                        "alice",
                        applicationId,
                        "unsafe key",
                        UpdateStatusRequest.builder()
                                .status("APPLIED")
                                .expectedVersion(1L)
                                .build(),
                        ApplicationCommandActor.user("alice")))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("Idempotency-Key");
        verify(transaction, never()).findReplay(any(), any(), any(), any());
    }

    @Test
    void exactReplayReturnsDurableOriginalOutcomeWithoutApplyingAgain() {
        ApplicationAppliedFreezeService service =
                new ApplicationAppliedFreezeService(transaction);
        UUID applicationId = UUID.randomUUID();
        UpdateStatusRequest request = UpdateStatusRequest.builder()
                .status("APPLIED")
                .expectedVersion(3L)
                .build();
        ApplicationRecordResponse original = ApplicationRecordResponse.builder()
                .id(applicationId)
                .version(4)
                .build();
        when(transaction.findReplay(
                        any(), any(), any(), any()))
                .thenReturn(Optional.of(original));

        assertThat(service.apply(
                        "alice",
                        applicationId,
                        "apply-1",
                        request,
                        ApplicationCommandActor.user("alice")))
                .isSameAs(original);
        verify(transaction, never()).apply(any(), any(), any(), any(), any(), any());
    }
}
