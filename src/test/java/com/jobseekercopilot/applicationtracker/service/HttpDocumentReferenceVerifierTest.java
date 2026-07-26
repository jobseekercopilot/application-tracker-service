package com.jobseekercopilot.applicationtracker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.exception.DocumentReferenceUnavailableException;
import com.jobseekercopilot.applicationtracker.exception.InvalidDocumentReferenceException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class HttpDocumentReferenceVerifierTest {

    private static final UUID DOCUMENT_ID =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID FAMILY_ID =
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void canonicalApprovedReferenceIsOwnerScopedAndMappedWithoutContent() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpDocumentReferenceVerifier verifier = new HttpDocumentReferenceVerifier(
                restTemplate, "http://document-store", "reader-secret");

        server.expect(
                        once(),
                        requestTo("http://document-store/api/v1/documents/"
                                + DOCUMENT_ID
                                + "/reference"))
                .andExpect(header("X-Service-Token", "reader-secret"))
                .andExpect(header("X-Document-Owner", "owner-123"))
                .andRespond(withSuccess(referenceJson("CV", "APPROVED"), MediaType.APPLICATION_JSON));

        var reference = verifier.verify(
                "owner-123", DOCUMENT_ID, "job-456", DocumentType.CV);

        assertThat(reference.getDocumentId()).isEqualTo(DOCUMENT_ID);
        assertThat(reference.getDocumentFamilyId()).isEqualTo(FAMILY_ID);
        assertThat(reference.getVersion()).isEqualTo(3);
        assertThat(reference.getContentSha256()).isEqualTo("a".repeat(64));
        server.verify();
    }

    @Test
    void wrongKindOrUnapprovedReferenceUsesOneNonEnumeratingValidationError() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpDocumentReferenceVerifier verifier = new HttpDocumentReferenceVerifier(
                restTemplate, "http://document-store", "reader-secret");
        server.expect(once(), requestTo(
                        "http://document-store/api/v1/documents/"
                                + DOCUMENT_ID
                                + "/reference"))
                .andRespond(withSuccess(
                        referenceJson("COVER_LETTER", "DRAFT"),
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> verifier.verify(
                        "owner-123", DOCUMENT_ID, "job-456", DocumentType.CV))
                .isInstanceOf(InvalidDocumentReferenceException.class)
                .hasMessage("Document reference is not eligible for this application.");
    }

    @Test
    void documentStoreFailureIsRetryableAndDoesNotExposeProviderDetails() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpDocumentReferenceVerifier verifier = new HttpDocumentReferenceVerifier(
                restTemplate, "http://document-store", "reader-secret");
        server.expect(once(), requestTo(
                        "http://document-store/api/v1/documents/"
                                + DOCUMENT_ID
                                + "/reference"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> verifier.verify(
                        "owner-123", DOCUMENT_ID, "job-456", DocumentType.CV))
                .isInstanceOf(DocumentReferenceUnavailableException.class)
                .hasMessage("Document reference validation is temporarily unavailable.");
    }

    private String referenceJson(String documentType, String lifecycleState) {
        return """
                {
                  "documentId": "%s",
                  "documentFamilyId": "%s",
                  "jobId": "job-456",
                  "documentType": "%s",
                  "version": 3,
                  "contentSha256": "%s",
                  "lifecycleState": "%s"
                }
                """.formatted(
                DOCUMENT_ID,
                FAMILY_ID,
                documentType,
                "a".repeat(64),
                lifecycleState);
    }
}
