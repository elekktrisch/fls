package ch.alpenflight.migrations.infra;

import ch.alpenflight.migrations.domain.MigrationUpload;
import ch.alpenflight.migrations.domain.MigrationUploadRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data implementation of {@link MigrationUploadRepository}.
 * Pre-tenant — no Hibernate {@code @TenantId} filter applies.
 */
public interface JpaMigrationUploadRepository
        extends JpaRepository<MigrationUpload, UUID>, MigrationUploadRepository {

    @Override
    @Query("select u from MigrationUpload u "
            + "where u.userId = :userId and u.state = ch.alpenflight.migrations.domain.MigrationUploadState.AWAITING_UPLOAD")
    Optional<MigrationUpload> findAwaitingByUser(@Param("userId") UUID userId);

    @Override
    @Query("select u from MigrationUpload u where u.id = :id")
    Optional<MigrationUpload> findById(@Param("id") UUID id);

    @Override
    @Query("select u from MigrationUpload u "
            + "where u.state = ch.alpenflight.migrations.domain.MigrationUploadState.AWAITING_UPLOAD "
            + "and u.expiresAt < :now")
    List<MigrationUpload> findExpired(@Param("now") Instant now);
}
