package ch.alpenflight.clubs.infra;

import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.clubs.domain.MemberStateRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaMemberStateRepository
        extends JpaRepository<MemberState, UUID>, MemberStateRepository {
}
