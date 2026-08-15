package ch.alpenflight.migrations.web;

import ch.alpenflight.migrations.application.MigrationBundleIngestService;
import ch.alpenflight.migrations.application.MigrationBundleIngestService.IngestOutcome;
import ch.alpenflight.migrations.application.MigrationBundleStatusService;
import ch.alpenflight.migrations.application.MigrationRunStatusView;
import ch.alpenflight.migrations.application.PreTenantUserLookup;
import ch.alpenflight.migrations.application.UnknownPrincipalException;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/migrations")
@Tag(name = "Migration bundle ingest",
        description = "Encrypted-bundle upload + streaming decrypt + ingest pipeline (S-141).")
public class MigrationBundleController {

    private static final String EMAIL_VERIFIED =
            "isAuthenticated() and principal.claims['email_verified'] == true";

    private final MigrationBundleIngestService ingestService;
    private final MigrationBundleStatusService statusService;
    private final PreTenantUserLookup userLookup;

    public MigrationBundleController(MigrationBundleIngestService ingestService,
                                     MigrationBundleStatusService statusService,
                                     PreTenantUserLookup userLookup) {
        this.ingestService = ingestService;
        this.statusService = statusService;
        this.userLookup = userLookup;
    }

    @Operation(summary = "Upload + decrypt + ingest an encrypted FLS migration bundle.")
    @PreAuthorize(EMAIL_VERIFIED)
    @PostMapping(value = "/{uploadId}/bundle",
            consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public IngestResponse uploadBundle(@PathVariable("uploadId") UUID uploadId,
                                       @AuthenticationPrincipal Jwt jwt,
                                       HttpServletRequest request) throws IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MigrationBundleIngestService.MAX_BUNDLE_BYTES) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_TOO_LARGE,
                    "Content-Length " + contentLength + " exceeds bundle-size cap "
                            + MigrationBundleIngestService.MAX_BUNDLE_BYTES);
        }
        UUID userId = userLookup.resolveUserId(jwt)
                .orElseThrow(() -> new UnknownPrincipalException(
                        "No t_user row for principal — verified-email signup expected"));
        UUID principalKeycloakSub = UUID.fromString(jwt.getSubject());
        try (InputStream body = boundedStream(request.getInputStream())) {
            IngestOutcome outcome = ingestService.ingest(
                    uploadId, userId, principalKeycloakSub, body);
            return new IngestResponse(outcome.deploymentId(), outcome.clubIds(), outcome.primaryClubId());
        }
    }

    private static InputStream boundedStream(InputStream raw) {
        return new InputStream() {
            private long readSoFar;

            @Override
            public int read() throws IOException {
                int next = raw.read();
                if (next == -1) {
                    return -1;
                }
                readSoFar++;
                if (readSoFar > MigrationBundleIngestService.MAX_BUNDLE_BYTES) {
                    throw new BundleIngestException(
                            BundleIngestErrorCode.BUNDLE_TOO_LARGE,
                            "Body exceeds " + MigrationBundleIngestService.MAX_BUNDLE_BYTES + " bytes");
                }
                return next;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int read = raw.read(b, off, len);
                if (read == -1) {
                    return -1;
                }
                readSoFar += read;
                if (readSoFar > MigrationBundleIngestService.MAX_BUNDLE_BYTES) {
                    throw new BundleIngestException(
                            BundleIngestErrorCode.BUNDLE_TOO_LARGE,
                            "Body exceeds " + MigrationBundleIngestService.MAX_BUNDLE_BYTES + " bytes");
                }
                return read;
            }

            @Override
            public void close() throws IOException {
                raw.close();
            }
        };
    }

    @Operation(summary = "Poll the current bundle-ingest run state for an upload.")
    @PreAuthorize(EMAIL_VERIFIED)
    @GetMapping(value = "/{uploadId}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public MigrationRunStatusView status(@PathVariable("uploadId") UUID uploadId,
                                         @AuthenticationPrincipal Jwt jwt) {
        UUID userId = userLookup.resolveUserId(jwt)
                .orElseThrow(() -> new UnknownPrincipalException(
                        "No t_user row for principal — verified-email signup expected"));
        return statusService.findStatus(uploadId, userId);
    }

    public record IngestResponse(UUID deploymentId, List<UUID> clubIds, UUID primaryClubId) {
        public IngestResponse {
            clubIds = List.copyOf(clubIds);
        }
    }
}
