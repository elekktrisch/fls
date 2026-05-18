package ch.alpenflight.clubs;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberStateRepository extends JpaRepository<MemberState, UUID> {
}
