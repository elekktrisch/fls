package ch.alpenflight.persons.infra;

import ch.alpenflight.persons.domain.PersonClub;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data binding for {@link PersonClub}. No application-layer consumer
 * — PersonClub mutations go through the {@link ch.alpenflight.persons.domain.Person}
 * aggregate (orphanRemoval + cascade carry the writes). The repository is
 * present so the S-024 leakage sweep can drive direct
 * {@code save / findById} calls against the tenant-scoped child entity to
 * verify Hibernate's {@code @TenantId} discriminator on {@code club_id}.
 */
public interface JpaPersonClubRepository extends JpaRepository<PersonClub, UUID> {
}
