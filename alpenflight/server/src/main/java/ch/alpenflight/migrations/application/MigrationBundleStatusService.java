package ch.alpenflight.migrations.application;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.migrations.domain.BundleIngestErrorCode;
import ch.alpenflight.migrations.domain.BundleIngestException;
import ch.alpenflight.migrations.domain.MigrationRun;
import ch.alpenflight.migrations.domain.MigrationRunRepository;
import ch.alpenflight.migrations.domain.MigrationUpload;
import ch.alpenflight.migrations.domain.MigrationUploadRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only service backing {@code GET /api/v1/migrations/{uploadId}/status}.
 * PK fetches only; p95 budget &lt; 100 ms per the perf plan.
 */
@Service
public class MigrationBundleStatusService {

    private final MigrationUploadRepository uploads;
    private final MigrationRunRepository runs;
    private final ClubRepository clubs;

    public MigrationBundleStatusService(MigrationUploadRepository uploads,
                                        MigrationRunRepository runs,
                                        ClubRepository clubs) {
        this.uploads = uploads;
        this.runs = runs;
        this.clubs = clubs;
    }

    @Transactional(readOnly = true)
    public MigrationRunStatusView findStatus(UUID uploadId, UUID principalUserId) {
        MigrationUpload upload = uploads.findById(uploadId)
                .orElseThrow(() -> new BundleIngestException(
                        BundleIngestErrorCode.BUNDLE_FORBIDDEN,
                        "Unknown uploadId " + uploadId));
        if (!upload.getUserId().equals(principalUserId)) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_FORBIDDEN,
                    "Upload " + uploadId + " not owned by caller");
        }
        Optional<MigrationRun> latest = runs.findLatestByUpload(uploadId);
        if (latest.isEmpty()) {
            throw new BundleIngestException(
                    BundleIngestErrorCode.BUNDLE_FORBIDDEN,
                    "No ingest run yet for upload " + uploadId);
        }
        MigrationRun run = latest.get();
        List<UUID> clubIds = run.getDeploymentId() != null
                ? clubs.findIdsByDeploymentId(run.getDeploymentId())
                : List.of();
        return MigrationRunStatusView.of(run, clubIds);
    }
}
