package com.jobseekercopilot.applicationtracker.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ErrorResponse {

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private int status;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private String message;

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private LocalDateTime timestamp;

    private List<String> errors;
}
