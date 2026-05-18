package ch.alpenflight.platform.id;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.EnumSet;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.hibernate.generator.EventTypeSets;

/**
 * Application-side UUID v7 generator wired via {@link UuidV7}. Hibernate
 * invokes {@link #generate} right before insert; the returned value lands
 * in the entity's id column. Time-ordered (v7) keys keep B-tree inserts
 * monotonic, which matters once S-028's cutover starts batching N×M users
 * per Deployment.
 *
 * <p>This generator is stateless and shareable — Hibernate caches one per
 * mapped attribute. Postgres receives the UUID as a binding parameter; no
 * {@code DEFAULT gen_random_uuid()} ever fires (forbidden by
 * `forbidden-migration-patterns.txt`).
 */
public final class FlsUuidV7Generator implements BeforeExecutionGenerator {

    @Override
    public Object generate(SharedSessionContractImplementor session,
                           Object owner,
                           Object currentValue,
                           EventType eventType) {
        return UuidCreator.getTimeOrderedEpoch();
    }

    @Override
    public EnumSet<EventType> getEventTypes() {
        return EventTypeSets.INSERT_ONLY;
    }
}
