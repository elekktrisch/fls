package ch.alpenflight.migrations.infra;

import ch.alpenflight.migrations.domain.MigrationRun;
import ch.alpenflight.migrations.domain.MigrationRunRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaMigrationRunRepository
        extends JpaRepository<MigrationRun, UUID>, MigrationRunRepository {

    @Override
    @Query("select r from MigrationRun r where r.id = :id")
    Optional<MigrationRun> findById(@Param("id") UUID id);

    @Query("select r from MigrationRun r "
            + "where r.uploadId = :uploadId "
            + "order by r.startedAt desc")
    java.util.List<MigrationRun> latestByUpload(@Param("uploadId") UUID uploadId, Limit limit);

    @Override
    default Optional<MigrationRun> findLatestByUpload(UUID uploadId) {
        return latestByUpload(uploadId, Limit.of(1)).stream().findFirst();
    }
}
