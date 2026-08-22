package com.jobseekercopilot.applicationtracker.controller;

import com.jobseekercopilot.applicationtracker.dto.ApplicationPersonalDataExport;
import com.jobseekercopilot.applicationtracker.service.ApplicationAccountLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ApplicationAccountLifecycleController {

    private final ApplicationAccountLifecycleService lifecycleService;

    @GetMapping("/api/v1/applications/account-export")
    @Operation(summary = "Export current owner's application data")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ApplicationPersonalDataExport> export(
            @AuthenticationPrincipal Jwt token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(lifecycleService.export(token.getSubject()));
    }

    @DeleteMapping("/internal/account-lifecycle/personal-data")
    @Hidden
    @Operation(summary = "Erase application-owned personal data")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> erase(@AuthenticationPrincipal Jwt token) {
        lifecycleService.erase(token.getSubject());
        return ResponseEntity.noContent().build();
    }
}
