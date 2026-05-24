package ch.alpenflight.clubs.domain;

import java.util.List;

/**
 * Domain port for {@link MemberState} reads. Implemented by
 * {@code ch.alpenflight.clubs.infra.JpaMemberStateRepository} which extends
 * both this interface and Spring Data's {@code JpaRepository<MemberState, UUID>}
 * — so the {@code application} / cross-module callers depend on the abstract
 * port (ADR 0023) while Spring Data still generates the runtime bean.
 *
 * <p>The {@code @TenantId} discriminator on {@link MemberState#getClubId()}
 * scopes every {@link #findAll()} call to the caller's tenant automatically.
 */
public interface MemberStateRepository {

    /** Tenant-scoped {@code findAll}. */
    List<MemberState> findAll();
}
