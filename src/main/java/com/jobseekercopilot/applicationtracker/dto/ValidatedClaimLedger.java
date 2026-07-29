package com.jobseekercopilot.applicationtracker.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(
        description = """
                Non-sensitive identity and digest of the exact claim ledger
                accepted by generation validation.
                """)
public record ValidatedClaimLedger(
        UUID ledgerId,
        String ledgerSha256,
        String policyVersion,
        String parserVersion) {
}
