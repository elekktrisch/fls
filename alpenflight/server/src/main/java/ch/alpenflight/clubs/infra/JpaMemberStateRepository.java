package ch.alpenflight.clubs.infra;

import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.clubs.domain.MemberStateRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data implementation of {@link MemberStateRepository} (the domain
 * port). Per ADR 0023 the application + cross-module callers depend on the
 * port; this interface keeps the Spring Data binding inside {@code infra/}.
 *
 * <p>Hibernate's {@code @TenantId} on {@link MemberState#getClubId()} scopes
 * the inherited {@link JpaRepository#findAll()} to the caller's tenant
 * automatically — no explicit predicate needed.
 */
public interface JpaMemberStateRepository
        extends JpaRepository<MemberState, UUID>, MemberStateRepository {
}
