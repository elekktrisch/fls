package ch.alpenflight.server.testsupport;

import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonClub;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRoleFlags;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders.SweepFixtureContext;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal-object factory for {@link PersonClub} consumed by the S-024
 * leakage sweep. PersonClub is aggregate-internal under the cross-tenant
 * {@link Person}; the {@code @TenantId} discriminator on {@code clubId}
 * is what makes it sweep-eligible.
 *
 * <p>The factory builds a transient {@link Person} parent per call and
 * attaches one {@link PersonClub} child via
 * {@link PersonClub#forSweepFixture}. The child's {@code clubId} is NOT
 * set — Hibernate's {@code @TenantId} resolver fills it on save (matching
 * the {@code MemberState} pattern). {@code CascadeType.PERSIST} on
 * {@code PersonClub.person} cascade-persists the parent at flush, so
 * {@code JpaPersonClubRepository.save(child)} writes both rows.
 *
 * <p>Sweep-created Person rows accumulate in the cross-tenant {@code person}
 * table (no per-test cleanup runs against cross-tenant data). Harmless —
 * the sweep asserts against specific row ids it just created.
 */
final class PersonClubSweepFactory {

    private static final AtomicInteger COUNTER = new AtomicInteger(0);

    private PersonClubSweepFactory() {}

    static PersonClub build(SweepFixtureContext ctx) {
        if (ctx == null) {
            throw new IllegalStateException("SweepFixtureContext is required");
        }
        Person p = Person.register(
                "Sweep" + COUNTER.incrementAndGet() + "-" + Long.toString(System.nanoTime(), 36),
                "Person",
                null);
        return PersonClub.forSweepFixture(p, PersonRoleFlags.none(), PersonNotificationPrefs.none(), true);
    }
}
