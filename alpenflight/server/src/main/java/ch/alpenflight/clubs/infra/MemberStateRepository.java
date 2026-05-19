package ch.alpenflight.clubs.infra;

import ch.alpenflight.clubs.domain.MemberState;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link MemberState}. No application-layer
 * consumer today — referenced only by the S-022 tenant-isolation IT
 * which exercises the {@code @TenantId} discriminator on this entity. A
 * domain port (analogous to
 * {@link ch.alpenflight.clubs.domain.ClubRepository}) will be extracted
 * when the first per-club member-status feature lands.
 */
public interface MemberStateRepository extends JpaRepository<MemberState, UUID> {
}
