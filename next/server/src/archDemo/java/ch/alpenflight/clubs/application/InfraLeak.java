package ch.alpenflight.clubs.application;

import ch.alpenflight.clubs.infra.JpaClubRepository;

/**
 * Deliberate ADR 0023 violation: a class in a {@code ..application..}
 * package depends on a class in a {@code ..infra..} package — the
 * adapter-not-port leak the {@code application_depends_on_domain_port_not_infra}
 * ArchUnit rule prevents. Application code MUST depend on the
 * {@link ch.alpenflight.clubs.domain.ClubRepository} port, not the Spring
 * Data implementation. Lives in the {@code archDemo} source set;
 * {@code verifyArchUnitFailsOnViolation} asserts the rule fires here.
 */
public class InfraLeak {

    @SuppressWarnings("unused")
    private final JpaClubRepository repository;

    public InfraLeak(JpaClubRepository repository) {
        this.repository = repository;
    }
}
