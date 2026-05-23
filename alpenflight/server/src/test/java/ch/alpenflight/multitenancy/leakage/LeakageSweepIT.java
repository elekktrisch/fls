package ch.alpenflight.multitenancy.leakage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantScopedEntityCatalog;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders.SweepFixtureContext;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.support.Repositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.context.support.GenericWebApplicationContext;

/**
 * The S-024 catalog-driven leakage sweep. For every {@code @TenantId}-bearing
 * entity discovered on the classpath:
 *
 * <ol>
 *   <li><strong>Negative.</strong> Insert under tenant A; under tenant B,
 *       both {@code findAll} and {@code findById} return empty.</li>
 *   <li><strong>Positive baseline.</strong> Same insert, read under A,
 *       row is visible — guards "test passes because everything is empty."</li>
 *   <li><strong>Fail-closed read.</strong> Under the {@code NO_TENANT}
 *       sentinel, {@code findAll} returns empty.</li>
 *   <li><strong>Fail-closed write.</strong> Under the sentinel, save fails
 *       at the {@code fk_<table>_club_id} FK (no nil-UUID row in club).
 *       Without this, a resolver that mistakenly returns {@code null}
 *       (rather than the sentinel) would silently un-filter writes.</li>
 * </ol>
 *
 * <p>Each operation runs through the entity's Spring Data
 * {@link JpaRepository}, which opens its own transaction. This is the
 * idiomatic shape — and it matches how production calls hit the resolver
 * (one tenant resolve per session), so the sweep tests what production
 * actually does.
 *
 * <p>Per-test cleanup deletes all tenant-scoped rows under the two seed
 * clubs (driven by the same catalog the sweep iterates), then re-seeds.
 * Per ADR 0021 — pre-clean by stable key, no {@code @AfterEach}.
 */
class LeakageSweepIT extends PostgresIntegrationTest {

    private static final UUID CLUB_A = UUID.fromString("019e30c3-2c00-7001-8000-0000000000c1");
    private static final UUID CLUB_B = UUID.fromString("019e30c3-2c00-7001-8000-0000000000c2");
    private static final String NAME_PREFIX = "IT_SWP_";
    private static final String KEY_PREFIX = "IT_S_";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private GenericWebApplicationContext appContext;

    private TwoClubFixture clubs;
    private SweepFixtureContext ctx;

    static Stream<Class<?>> tenantScopedEntities() {
        return TenantScopedEntityCatalog.discoverTenantScopedEntities().stream();
    }

    @BeforeEach
    void seed() {
        this.clubs = new TwoClubFixture(jdbc, CLUB_A, CLUB_B, NAME_PREFIX, KEY_PREFIX);
        clubs.seed();
        TenantTestContext.clear();
        this.ctx = new SweepFixtureContext(jdbc);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantScopedEntities")
    @DisplayName("create-as-A is invisible to B (findAll + findById)")
    <E> void tenant_scoped_create_in_A_invisible_to_B(Class<E> entityClass) {
        JpaRepository<E, UUID> repo = repositoryFor(entityClass);
        E persisted = saveAs(entityClass, repo, CLUB_A);
        UUID id = idOf(persisted);

        TenantTestContext.runAs(CLUB_B, () -> {
            assertThat(repo.findById(id))
                    .as("findById under B must not see A's row for %s", entityClass.getSimpleName())
                    .isEmpty();
            assertThat(repo.findAll())
                    .as("findAll under B must not see A's row for %s", entityClass.getSimpleName())
                    .extracting(LeakageSweepIT::idOfQuiet)
                    .doesNotContain(id);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantScopedEntities")
    @DisplayName("create-as-A is visible to A (positive baseline)")
    <E> void tenant_scoped_returns_own_rows_under_self(Class<E> entityClass) {
        JpaRepository<E, UUID> repo = repositoryFor(entityClass);
        E persisted = saveAs(entityClass, repo, CLUB_A);
        UUID id = idOf(persisted);

        TenantTestContext.runAs(CLUB_A, () -> {
            assertThat(repo.findAll())
                    .as("positive baseline — findAll under A must include A's row for %s",
                            entityClass.getSimpleName())
                    .extracting(LeakageSweepIT::idOfQuiet)
                    .contains(id);
            assertThat(repo.findById(id))
                    .as("positive baseline — findById under A must return A's row for %s",
                            entityClass.getSimpleName())
                    .isPresent();
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantScopedEntities")
    @DisplayName("NO_TENANT sentinel — findAll returns zero rows")
    <E> void tenant_scoped_no_tenant_sentinel_read_returns_zero(Class<E> entityClass) {
        JpaRepository<E, UUID> repo = repositoryFor(entityClass);
        saveAs(entityClass, repo, CLUB_A);

        TenantTestContext.runUnscoped(() -> assertThat(repo.findAll())
                .as("NO_TENANT read on %s must be empty (Hibernate filters on nil UUID)",
                        entityClass.getSimpleName())
                .isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantScopedEntities")
    @DisplayName("NO_TENANT sentinel — save fails at FK constraint")
    <E> void tenant_scoped_no_tenant_sentinel_insert_fails_on_fk(Class<E> entityClass) {
        JpaRepository<E, UUID> repo = repositoryFor(entityClass);
        Function<SweepFixtureContext, E> builder = builderFor(entityClass);

        assertThatThrownBy(() -> TenantTestContext.runUnscoped(() ->
                repo.save(builder.apply(ctx))))
                .as("Save under NO_TENANT on %s must fail at fk_<table>_club_id",
                        entityClass.getSimpleName())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private <E> E saveAs(Class<E> entityClass, JpaRepository<E, UUID> repo, UUID clubId) {
        Function<SweepFixtureContext, E> builder = builderFor(entityClass);
        return TenantTestContext.runAs(clubId, () -> repo.save(builder.apply(ctx)));
    }

    private <E> Function<SweepFixtureContext, E> builderFor(Class<E> entityClass) {
        Function<SweepFixtureContext, E> builder = TenantScopedRowBuilders.builderFor(entityClass);
        assertThat(builder)
                .as("Row builder must be registered for %s — see TenantScopedRowBuilders",
                        entityClass.getName())
                .isNotNull();
        return builder;
    }

    @SuppressWarnings("unchecked")
    private <E> JpaRepository<E, UUID> repositoryFor(Class<E> entityClass) {
        Repositories repos = new Repositories(appContext);
        Object repo = repos.getRepositoryFor(entityClass).orElse(null);
        assertThat(repo)
                .as("Spring Data must expose a JpaRepository for %s", entityClass.getName())
                .isInstanceOf(JpaRepository.class);
        return (JpaRepository<E, UUID>) repo;
    }

    private static UUID idOf(Object entity) {
        try {
            for (var f : entity.getClass().getDeclaredFields()) {
                if (f.isAnnotationPresent(jakarta.persistence.Id.class)) {
                    f.setAccessible(true);
                    return (UUID) f.get(entity);
                }
            }
            throw new AssertionError("No @Id field on " + entity.getClass().getName());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot extract @Id from " + entity.getClass().getName(), e);
        }
    }

    /** Same as {@link #idOf} but typed as {@code Object → Object} for assertj's {@code extracting}. */
    private static Object idOfQuiet(Object entity) {
        return idOf(entity);
    }

}
