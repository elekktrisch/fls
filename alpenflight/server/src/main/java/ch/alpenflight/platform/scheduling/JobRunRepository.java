package ch.alpenflight.platform.scheduling;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence port for {@link JobRun}. Platform / cross-tenant store — no
 * tenant scope (jobs run unscoped; the console is sysadmin-gated). The flat
 * {@code platform.scheduling} kernel package doesn't carry the 4-layer
 * template, so this Spring Data interface is itself the port (mirrors the
 * platform convention, not the business-module domain/infra split).
 */
public interface JobRunRepository extends JpaRepository<JobRun, UUID> {

    @Query("select r from JobRun r where r.jobName = :jobName order by r.startedAt desc")
    List<JobRun> findByJobNameOrderByStartedAtDesc(@Param("jobName") String jobName, Limit limit);

    /** Most recent run for a job name — drives the console's last-run status. */
    default Optional<JobRun> findLatestByJobName(String jobName) {
        return findByJobNameOrderByStartedAtDesc(jobName, Limit.of(1)).stream().findFirst();
    }
}
