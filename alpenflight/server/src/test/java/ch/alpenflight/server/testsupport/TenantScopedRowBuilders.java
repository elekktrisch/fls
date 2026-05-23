package ch.alpenflight.server.testsupport;

import ch.alpenflight.clubs.domain.MemberState;
import ch.alpenflight.locations.domain.Location;
import jakarta.persistence.EntityManager;
import java.util.Map;
import java.util.function.Function;

/**
 * Per-entity row-builder registry consumed by the S-024 leakage sweep. One
 * entry per {@code @TenantId}-bearing aggregate root: the builder persists a
 * minimal row under whatever tenant is currently active on the resolver.
 *
 * <p>Builders use {@link EntityManager#persist(Object)} directly — bypassing
 * service-layer validators — because the sweep tests the
 * {@code @TenantId} discriminator + FK behaviour, not business rules. The
 * tenant column is populated reflectively by Hibernate from the resolver;
 * builders MUST NOT set it themselves.
 *
 * <p>New {@code @TenantId} entity in a future story = add a builder here in
 * the same PR; the floor assertion at sweep boot catches the mismatch if
 * forgotten.
 */
public final class TenantScopedRowBuilders {

    private TenantScopedRowBuilders() {}

    /** Persisted natural-key prefix; ID search filters strip this for cleanup. */
    public static final String SWEEP_PREFIX = "IT_SWEEP_";

    /** Returns the row builder for {@code entityClass}, or null if none registered. */
    @SuppressWarnings("unchecked")
    public static <E> Function<EntityManager, E> builderFor(Class<E> entityClass) {
        return (Function<EntityManager, E>) BUILDERS.get(entityClass);
    }

    /** All registered entity classes (test-side roster). */
    public static java.util.Set<Class<?>> registered() {
        return BUILDERS.keySet();
    }

    private static final Map<Class<?>, Function<EntityManager, ?>> BUILDERS = Map.of(
            MemberState.class, em -> {
                MemberState row = new MemberState(uniqueName("MS"));
                em.persist(row);
                return row;
            },
            Location.class, em -> {
                Location row = LocationSweepFactory.build(em);
                em.persist(row);
                return row;
            }
    );

    private static String uniqueName(String label) {
        return SWEEP_PREFIX + label + "_" + Long.toString(System.nanoTime(), 36);
    }
}
