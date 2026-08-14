package ch.alpenflight.server.testsupport;

import ch.alpenflight.persons.domain.Person;
import ch.alpenflight.persons.domain.PersonClub;
import ch.alpenflight.persons.domain.PersonNotificationPrefs;
import ch.alpenflight.persons.domain.PersonRoleFlags;
import java.util.concurrent.atomic.AtomicInteger;

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
