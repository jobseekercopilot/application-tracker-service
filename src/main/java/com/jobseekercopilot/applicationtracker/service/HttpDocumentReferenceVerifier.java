package com.jobseekercopilot.applicationtracker.service;

import com.jobseekercopilot.applicationtracker.dto.DocumentType;
import com.jobseekercopilot.applicationtracker.dto.DocumentVersionReference;
import com.jobseekercopilot.applicationtracker.exception.DocumentReferenceUnavailableException;
import com.jobseekercopilot.applicationtracker.exception.InvalidDocumentReferenceException;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpServerErrorException;

@Component
public class HttpDocumentReferenceVerifier implements DocumentReferenceVerifier {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";
    private static final String OWNER_HEADER = "X-Document-Owner";
    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");

    private final RestTemplate restTemplate;
    private final String documentStoreBaseUrl;
    private final String readerToken;

    @Autowired
    public HttpDocumentReferenceVerifier(
            RestTemplateBuilder builder,
            @Value("${services.document-store-service.base-url:}") String documentStoreBaseUrl,
            @Value("${services.document-store-service.reader-token:}") String readerToken) {
        this(
                builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(3))
                .build(),
                documentStoreBaseUrl,
                readerToken);
    }

    HttpDocumentReferenceVerifier(
            RestTemplate restTemplate,
            String documentStoreBaseUrl,
            String readerToken) {
        this.restTemplate = restTemplate;
        this.documentStoreBaseUrl = stripTrailingSlash(documentStoreBaseUrl);
        this.readerToken = readerToken;
    }

    @Override
    public DocumentVersionReference verify(
            String ownerId,
            UUID documentId,
            String expectedJobId,
            DocumentType expectedType) {
        if (!StringUtils.hasText(documentStoreBaseUrl)
                || !StringUtils.hasText(readerToken)) {
            throw new DocumentReferenceUnavailableException();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(SERVICE_TOKEN_HEADER, readerToken);
        headers.set(OWNER_HEADER, ownerId);
        try {
            ResponseEntity<DocumentStoreReferenceResponse> response =
                    restTemplate.exchange(
                            documentStoreBaseUrl
                                    + "/api/v1/documents/{documentId}/reference",
                            HttpMethod.GET,
                            new HttpEntity<>(headers),
                            DocumentStoreReferenceResponse.class,
                            documentId);
            DocumentStoreReferenceResponse reference = response.getBody();
            if (!eligible(reference, documentId, expectedJobId, expectedType)) {
                throw new InvalidDocumentReferenceException();
            }
            return DocumentVersionReference.builder()
                    .documentId(reference.getDocumentId())
                    .documentFamilyId(reference.getDocumentFamilyId())
                    .jobId(reference.getJobId())
                    .documentType(reference.getDocumentType())
                    .version(reference.getVersion())
                    .contentSha256(reference.getContentSha256())
                    .build();
        } catch (HttpClientErrorException exception) {
            throw new InvalidDocumentReferenceException();
        } catch (HttpServerErrorException exception) {
            throw new DocumentReferenceUnavailableException();
        } catch (RestClientException exception) {
            throw new DocumentReferenceUnavailableException();
        }
    }

    private boolean eligible(
            DocumentStoreReferenceResponse reference,
            UUID documentId,
            String expectedJobId,
            DocumentType expectedType) {
        return reference != null
                && documentId.equals(reference.getDocumentId())
                && reference.getDocumentFamilyId() != null
                && expectedJobId.equals(reference.getJobId())
                && expectedType == reference.getDocumentType()
                && reference.getVersion() != null
                && reference.getVersion() >= 1
                && reference.getContentSha256() != null
                && SHA_256.matcher(reference.getContentSha256()).matches()
                && "APPROVED".equals(reference.getLifecycleState());
    }

    private static String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    static class DocumentStoreReferenceResponse {
        private UUID documentId;
        private UUID documentFamilyId;
        private String jobId;
        private DocumentType documentType;
        private Integer version;
        private String contentSha256;
        private String lifecycleState;
    }
}
