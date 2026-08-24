package ch.alpenflight.multitenancy.leakage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.clubs.domain.ClubRepository;
import ch.alpenflight.deployments.domain.Deployment;
import ch.alpenflight.referencedata.domain.ClubStateRepository;
import ch.alpenflight.referencedata.domain.CountryRepository;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.SweepFixtureContext;
import ch.alpenflight.server.testsupport.TenantScopedEntityCatalog;
import ch.alpenflight.server.testsupport.TenantScopedRowBuilders;
import ch.alpenflight.server.testsupport.TenantTestContext;
import ch.alpenflight.server.testsupport.TwoClubFixture;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
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

class LeakageSweepIT extends PostgresIntegrationTest {

    private static final String NAME_PREFIX = "IT_SWP_";
    private static final String KEY_PREFIX = "IT_S_";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private GenericWebApplicationContext appContext;
    @Autowired private ClubRepository clubRepo;
    @Autowired private CountryRepository countries;
    @Autowired private ClubStateRepository clubStates;

    private TwoClubFixture clubs;
    private SweepFixtureContext ctx;
    private UUID clubA;
    private UUID clubB;
    private UUID sandboxSeatClub;

    static Stream<Class<?>> tenantScopedEntities() {
        return TenantScopedEntityCatalog.discoverTenantScopedEntities().stream();
    }

    @BeforeEach
    void seed() {
        this.clubs = new TwoClubFixture(jdbc, clubRepo, countries, clubStates, NAME_PREFIX, KEY_PREFIX);
        clubs.seed();
        this.clubA = clubs.clubA();
        this.clubB = clubs.clubB();
        this.sandboxSeatClub =
                clubs.seedAdditionalClubInDeployment(Deployment.SANDBOX_ID, "sandboxseat");
        TenantTestContext.clear();
        this.ctx = new SweepFixtureContext(appContext);
    }

    @AfterEach
    void deleteTheSandboxDeploymentClubsThisTestSeeded() {
        clubs.deleteEveryAdditionalDeploymentClubThisFixtureSeeded();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantScopedEntities")
    @DisplayName("create-as-A is invisible to B (findAll + findById)")
    <E> void tenant_scoped_create_in_A_invisible_to_B(Class<E> entityClass) {
        JpaRepository<E, UUID> repo = repositoryFor(entityClass);
        E persisted = saveAs(entityClass, repo, clubA);
        UUID id = idOf(persisted);

        TenantTestContext.runAs(clubB, () -> {
            assertThat(repo.findById(id))
                    .as("findById under B must not see A's row for %s", entityClass.getSimpleName())
                    .isEmpty();
            assertThat(repo.findAll())
                    .as("findAll under B must not see A's row for %s", entityClass.getSimpleName())
                    .extracting(LeakageSweepIT::idOfTypedForAssertJExtracting)
                    .doesNotContain(id);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantScopedEntities")
    @DisplayName("create-as-A is visible to A (positive baseline)")
    <E> void tenant_scoped_returns_own_rows_under_self(Class<E> entityClass) {
        JpaRepository<E, UUID> repo = repositoryFor(entityClass);
        E persisted = saveAs(entityClass, repo, clubA);
        UUID id = idOf(persisted);

        TenantTestContext.runAs(clubA, () -> {
            assertThat(repo.findAll())
                    .as("positive baseline — findAll under A must include A's row for %s",
                            entityClass.getSimpleName())
                    .extracting(LeakageSweepIT::idOfTypedForAssertJExtracting)
                    .contains(id);
            assertThat(repo.findById(id))
                    .as("positive baseline — findById under A must return A's row for %s",
                            entityClass.getSimpleName())
                    .isPresent();
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantScopedEntities")
    @DisplayName("create-in-a-sandbox-seat is invisible to a real club")
    <E> void tenant_scoped_create_in_a_sandbox_seat_invisible_to_a_real_club(Class<E> entityClass) {
        JpaRepository<E, UUID> repo = repositoryFor(entityClass);
        UUID sandboxRow = idOf(saveAs(entityClass, repo, sandboxSeatClub));
        UUID ownRow = idOf(saveAs(entityClass, repo, clubA));

        TenantTestContext.runAs(clubA, () -> {
            assertThat(repo.findById(sandboxRow))
                    .as("findById in a real club must not see a sandbox seat's row for %s",
                            entityClass.getSimpleName())
                    .isEmpty();
            assertThat(repo.findAll())
                    .as("findAll in a real club must exclude the sandbox seat's row for %s "
                            + "and still include its own", entityClass.getSimpleName())
                    .extracting(LeakageSweepIT::idOfTypedForAssertJExtracting)
                    .contains(ownRow)
                    .doesNotContain(sandboxRow);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantScopedEntities")
    @DisplayName("create-in-a-real-club is invisible to a sandbox seat")
    <E> void tenant_scoped_create_in_a_real_club_invisible_to_a_sandbox_seat(Class<E> entityClass) {
        JpaRepository<E, UUID> repo = repositoryFor(entityClass);
        UUID realRow = idOf(saveAs(entityClass, repo, clubA));
        UUID ownRow = idOf(saveAs(entityClass, repo, sandboxSeatClub));

        TenantTestContext.runAs(sandboxSeatClub, () -> {
            assertThat(repo.findById(realRow))
                    .as("findById in a sandbox seat must not see a real club's row for %s",
                            entityClass.getSimpleName())
                    .isEmpty();
            assertThat(repo.findAll())
                    .as("findAll in a sandbox seat must exclude the real club's row for %s "
                            + "and still include its own", entityClass.getSimpleName())
                    .extracting(LeakageSweepIT::idOfTypedForAssertJExtracting)
                    .contains(ownRow)
                    .doesNotContain(realRow);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tenantScopedEntities")
    @DisplayName("NO_TENANT sentinel — findAll returns zero rows")
    <E> void tenant_scoped_no_tenant_sentinel_read_returns_zero(Class<E> entityClass) {
        JpaRepository<E, UUID> repo = repositoryFor(entityClass);
        saveAs(entityClass, repo, clubA);

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
        String tableName = TenantScopedEntityCatalog.resolveTableName(entityClass);
        String tenantCol = TenantScopedEntityCatalog.resolveTenantColumnName(entityClass);
        String legacyTableStem = tableName.startsWith("t_") ? tableName.substring(2) : tableName;
        String expectedFkName = "fk_" + legacyTableStem + "_" + tenantCol;

        assertThatThrownBy(() -> TenantTestContext.runUnscoped(() ->
                repo.save(builder.apply(ctx))))
                .as("Save under NO_TENANT on %s must fail at %s specifically — pinning the FK "
                        + "name rather than accepting any DataIntegrityViolation means a resolver "
                        + "that returns null instead of the sentinel cannot pass by tripping some "
                        + "other constraint", entityClass.getSimpleName(), expectedFkName)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(expectedFkName);
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

    private static Object idOfTypedForAssertJExtracting(Object entity) {
        return idOf(entity);
    }

}
