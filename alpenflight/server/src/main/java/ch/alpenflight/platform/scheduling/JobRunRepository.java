package ch.alpenflight.platform.scheduling;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRunRepository extends JpaRepository<JobRun, UUID> {

    @Query("select r from JobRun r where r.jobName = :jobName order by r.startedAt desc")
    List<JobRun> findByJobNameOrderByStartedAtDesc(@Param("jobName") String jobName, Limit limit);

    default Optional<JobRun> findLatestByJobName(String jobName) {
        return findByJobNameOrderByStartedAtDesc(jobName, Limit.of(1)).stream().findFirst();
    }
}
