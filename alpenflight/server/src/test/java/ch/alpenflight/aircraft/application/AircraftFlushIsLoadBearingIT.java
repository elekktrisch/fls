package ch.alpenflight.aircraft.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.aircraft.application.AircraftDtos.AircraftCounterRecordRequest;
import ch.alpenflight.aircraft.application.AircraftDtos.AircraftStateChangeRequest;
import ch.alpenflight.aircraft.domain.Aircraft;
import ch.alpenflight.aircraft.domain.AircraftOperatingCounter;
import ch.alpenflight.aircraft.domain.AircraftRepository;
import ch.alpenflight.aircraft.domain.AircraftStateHistoryEntry;
import ch.alpenflight.platform.id.AircraftId;
import ch.alpenflight.platform.id.AircraftStateId;
import ch.alpenflight.server.testsupport.PostgresIntegrationTest;
import ch.alpenflight.server.testsupport.TenantTestContext;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.repository.query.QueryCreationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AircraftFlushIsLoadBearingIT extends PostgresIntegrationTest {

    private static final String NAME_SPRING_DATA_SATISFIES_FROM_ITS_BASE_CLASS = "flush";

    private static final UUID SEED_CLUB =
            UUID.fromString("019e30c3-2c00-7001-8000-000000000001");

    private static final String WHY_THE_FLUSH_KEEPS_ITS_LITERAL_NAME =
            "AircraftRepository.flush() must keep the literal name 'flush'. Spring Data matches "
                    + "'flush' against JpaRepository.flush by signature and serves it from "
                    + "SimpleJpaRepository. A renamed method reaches the derived-query parser "
                    + "instead. Three renames were planted against real Postgres and scored. A "
                    + "name the parser rejects, 'flushPendingAircraftWrites', reds repository "
                    + "creation with QueryCreationException 'No property "
                    + "flushPendingAircraftWrites found for type Aircraft'; the whole application "
                    + "context then fails to load. A name the parser accepts, "
                    + "'deleteByDeletedOnNotNull', starts the context with no warning and binds a "
                    + "derived DELETE in place of the flush; only this rule and the ordering rule "
                    + "below caught it. Deleting the method reds javac at the five call sites, and "
                    + "reds this rule with NoSuchMethodException once a caller is deleted too. "
                    + "Correction to the filed rider — the rider states that a rename fails the "
                    + "start. That holds for one of the three classes. The accepted rename is the "
                    + "dangerous one, because it starts and silently deletes rows.";

    private static final String WHY_THE_CASCADED_CHILD_NEEDS_THE_FLUSH =
            "AircraftsService.changeAircraftState and AircraftsService.recordAircraftCounter call "
                    + "no save(). The Aircraft is already managed, and the new child reaches the "
                    + "persistence context through a cascaded @OneToMany collection. Hibernate "
                    + "cascades that collection at flush, so @GeneratedValue(strategy = UUID) "
                    + "assigns the child id at flush and not at the domain call. "
                    + "AircraftMapper.toStateResponse and AircraftMapper.toCounterResponse read "
                    + "that id through Objects.requireNonNull. Measured with the flush deleted: "
                    + "both call sites throw NullPointerException 'Cannot map an unpersisted "
                    + "state-history entry', so the request gets 500. Correction to the filed "
                    + "rider — the rider blames save() routing through EntityManager.merge. No "
                    + "save() runs at these two call sites. Where save() does run, on the parent "
                    + "Aircraft, persist assigns the parent id with no flush, so merge is not the "
                    + "mechanism anywhere in this seam. The child identity is.";

    private static final String WHY_THE_CLOSING_UPDATE_NEEDS_ITS_OWN_FLUSH =
            "AircraftsService.changeAircraftState must flush between closeCurrentStatePeriodAt and "
                    + "openStatePeriod. Hibernate executes the insert of the new child before the "
                    + "update that closes the previous period, because one flush orders inserts "
                    + "ahead of updates. The partial unique index "
                    + "ux_aas_current_state_per_aircraft allows one row per aircraft with valid_to "
                    + "null. Measured against real Postgres: one flush for both changes reds with "
                    + "'duplicate key value violates unique constraint "
                    + "ux_aas_current_state_per_aircraft'. The intermediate flush is an ordering "
                    + "device, not an identity device.";

    private static final String WHY_THE_WRITE_FLUSHES_INSIDE_THE_TRY =
            "AircraftsService.persist and AircraftsService.transferOwnership must flush inside "
                    + "their try block. save() only makes the Aircraft persistent; Hibernate defers "
                    + "the statement to the next flush, and with no explicit flush that is the "
                    + "commit. A commit runs after the catch block, so the "
                    + "DataIntegrityViolationException escapes as a 500 in place of "
                    + "DuplicateImmatriculationException (ux_aircraft_immatriculation) or "
                    + "InvalidAircraftReferenceException (fk_aircraft_owner_club_id). Measured "
                    + "against real Postgres: a duplicate immatriculation raises nothing at save() "
                    + "and raises DataIntegrityViolationException at commit.";

    private static final String RESIDUAL_LIMIT_OF_THESE_MEASUREMENTS =
            " Residual limit — this test reads the shipped declaration and scores three planted "
                    + "renames against a live JpaRepositoryFactory. It cannot enumerate every "
                    + "parseable rename, so it proves the class, not each member of it. It pins "
                    + "AircraftRepository only; the other repositories that flush inside a try "
                    + "block carry the same seam and no rule. registerAircraft also pre-checks the "
                    + "immatriculation with a query, so the flush conversion at that call site "
                    + "covers the race loser only.";

    @Autowired JdbcTemplate jdbc;
    @Autowired AircraftRepository aircrafts;
    @Autowired AircraftsService aircraftsService;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;

    interface FlushNamedAsSpringDataNamesIt extends JpaRepository<Aircraft, UUID> {
        @Override
        void flush();
    }

    interface FlushRenamedToANameTheDerivedQueryParserRejects
            extends JpaRepository<Aircraft, UUID> {
        void flushPendingAircraftWrites();
    }

    interface FlushRenamedToANameTheDerivedQueryParserAccepts
            extends JpaRepository<Aircraft, UUID> {
        void deleteByDeletedOnNotNull();
    }

    @Test
    void theRepositoryFlushKeepsTheNameThatSpringDataSatisfiesFromItsBaseClass() {
        assertThatCode(() -> AircraftRepository.class
                .getDeclaredMethod(NAME_SPRING_DATA_SATISFIES_FROM_ITS_BASE_CLASS))
                .as(WHY_THE_FLUSH_KEEPS_ITS_LITERAL_NAME + RESIDUAL_LIMIT_OF_THESE_MEASUREMENTS)
                .doesNotThrowAnyException();

        Method declared = declaredFlushOf(AircraftRepository.class);
        Method baseClassFlush = declaredFlushOf(JpaRepository.class);
        assertThat(declared.getReturnType())
                .as(WHY_THE_FLUSH_KEEPS_ITS_LITERAL_NAME)
                .isEqualTo(baseClassFlush.getReturnType());
        assertThat(declared.getParameterCount())
                .as(WHY_THE_FLUSH_KEEPS_ITS_LITERAL_NAME)
                .isEqualTo(baseClassFlush.getParameterCount());

        JpaRepositoryFactory factory = new JpaRepositoryFactory(entityManager);

        assertThatCode(() -> factory.getRepository(FlushNamedAsSpringDataNamesIt.class))
                .as(WHY_THE_FLUSH_KEEPS_ITS_LITERAL_NAME)
                .doesNotThrowAnyException();

        assertThatThrownBy(() ->
                factory.getRepository(FlushRenamedToANameTheDerivedQueryParserRejects.class))
                .as(WHY_THE_FLUSH_KEEPS_ITS_LITERAL_NAME)
                .isInstanceOf(QueryCreationException.class)
                .hasMessageContaining("flushPendingAircraftWrites");

        assertThat(factory.getRepository(FlushRenamedToANameTheDerivedQueryParserAccepts.class))
                .as(WHY_THE_FLUSH_KEEPS_ITS_LITERAL_NAME)
                .isNotNull();
    }

    @Test
    void theCascadedStateEntryAndCounterCarryNoIdUntilTheFlushRuns() {
        UUID stateId = anyAircraftStateId();
        UUID aircraftId = seedAircraft();

        newTransaction().execute(status -> {
            Aircraft a = aircrafts.findActiveById(aircraftId).orElseThrow();

            AircraftStateHistoryEntry entry = a.openStatePeriod(
                    stateId, Instant.parse("2026-01-01T10:00:00Z"), null, null);
            assertThat(entry.getId())
                    .as(WHY_THE_CASCADED_CHILD_NEEDS_THE_FLUSH)
                    .isNull();
            assertThatThrownBy(() -> AircraftMapper.toStateResponse(entry))
                    .as(WHY_THE_CASCADED_CHILD_NEEDS_THE_FLUSH)
                    .isInstanceOf(NullPointerException.class);
            aircrafts.flush();
            assertThat(entry.getId())
                    .as(WHY_THE_CASCADED_CHILD_NEEDS_THE_FLUSH)
                    .isNotNull();

            AircraftOperatingCounter counter = a.recordCounter(
                    Instant.parse("2026-01-01T11:00:00Z"), 1, null, null, 60L, null, null, null);
            assertThat(counter.getId())
                    .as(WHY_THE_CASCADED_CHILD_NEEDS_THE_FLUSH)
                    .isNull();
            assertThatThrownBy(() -> AircraftMapper.toCounterResponse(counter))
                    .as(WHY_THE_CASCADED_CHILD_NEEDS_THE_FLUSH)
                    .isInstanceOf(NullPointerException.class);
            aircrafts.flush();
            assertThat(counter.getId())
                    .as(WHY_THE_CASCADED_CHILD_NEEDS_THE_FLUSH)
                    .isNotNull();

            status.setRollbackOnly();
            return null;
        });
    }

    @Test
    void theShippedStateChangeAndCounterCallSitesReturnAPersistedId() {
        UUID stateId = anyAircraftStateId();
        UUID aircraftId = seedAircraft();

        TenantTestContext.runAs(SEED_CLUB, () -> {
            assertThat(aircraftsService.changeAircraftState(new AircraftId(aircraftId),
                            new AircraftStateChangeRequest(new AircraftStateId(stateId),
                                    Instant.parse("2026-02-01T10:00:00Z"), null, null))
                    .id())
                    .as(WHY_THE_CASCADED_CHILD_NEEDS_THE_FLUSH)
                    .isNotNull();

            assertThat(aircraftsService.recordAircraftCounter(new AircraftId(aircraftId),
                            new AircraftCounterRecordRequest(
                                    Instant.parse("2026-02-01T11:00:00Z"),
                                    1, null, null, 60L, null, null, null))
                    .id())
                    .as(WHY_THE_CASCADED_CHILD_NEEDS_THE_FLUSH)
                    .isNotNull();
        });
    }

    @Test
    void theClosingUpdateReachesPostgresBeforeTheOpeningInsert() {
        List<UUID> states = twoAircraftStateIds();
        UUID aircraftId = seedAircraft();
        TenantTestContext.runAs(SEED_CLUB, () ->
                aircraftsService.changeAircraftState(new AircraftId(aircraftId),
                        new AircraftStateChangeRequest(new AircraftStateId(states.get(0)),
                                Instant.parse("2026-03-01T10:00:00Z"), null, null)));

        assertThatThrownBy(() -> newTransaction().execute(status -> {
            Aircraft a = aircrafts.findActiveById(aircraftId).orElseThrow();
            a.closeCurrentStatePeriodAt(Instant.parse("2026-03-01T12:00:00Z"));
            a.openStatePeriod(states.get(1), Instant.parse("2026-03-01T12:00:00Z"), null, null);
            aircrafts.flush();
            status.setRollbackOnly();
            return null;
        }))
                .as(WHY_THE_CLOSING_UPDATE_NEEDS_ITS_OWN_FLUSH)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_aas_current_state_per_aircraft");

        assertThatCode(() -> TenantTestContext.runAs(SEED_CLUB, () ->
                aircraftsService.changeAircraftState(new AircraftId(aircraftId),
                        new AircraftStateChangeRequest(new AircraftStateId(states.get(1)),
                                Instant.parse("2026-03-01T12:00:00Z"), null, null))))
                .as(WHY_THE_CLOSING_UPDATE_NEEDS_ITS_OWN_FLUSH)
                .doesNotThrowAnyException();
    }

    @Test
    void theDuplicateImmatriculationOnlyRaisesInsideTheTryWhenTheFlushRuns() {
        UUID aircraftType = anyAircraftTypeId();
        String immatriculation = uniqueImmatriculation();
        newTransaction().execute(status -> {
            aircrafts.save(newAircraft(aircraftType, immatriculation));
            aircrafts.flush();
            return null;
        });

        assertThatThrownBy(() -> newTransaction().execute(status -> {
            assertThatCode(() -> aircrafts.save(newAircraft(aircraftType, immatriculation)))
                    .as(WHY_THE_WRITE_FLUSHES_INSIDE_THE_TRY)
                    .doesNotThrowAnyException();
            return null;
        }))
                .as(WHY_THE_WRITE_FLUSHES_INSIDE_THE_TRY)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_aircraft_immatriculation");

        assertThatThrownBy(() -> newTransaction().execute(status -> {
            aircrafts.save(newAircraft(aircraftType, immatriculation));
            aircrafts.flush();
            return null;
        }))
                .as(WHY_THE_WRITE_FLUSHES_INSIDE_THE_TRY)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_aircraft_immatriculation");
    }

    private TransactionTemplate newTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static Method declaredFlushOf(Class<?> owner) {
        try {
            return owner.getDeclaredMethod(NAME_SPRING_DATA_SATISFIES_FROM_ITS_BASE_CLASS);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(WHY_THE_FLUSH_KEEPS_ITS_LITERAL_NAME, e);
        }
    }

    private UUID anyAircraftStateId() {
        return jdbc.queryForObject("SELECT id FROM t_aircraft_state ORDER BY code LIMIT 1",
                UUID.class);
    }

    private List<UUID> twoAircraftStateIds() {
        return jdbc.queryForList("SELECT id FROM t_aircraft_state ORDER BY code LIMIT 2",
                UUID.class);
    }

    private UUID anyAircraftTypeId() {
        return jdbc.queryForObject("SELECT id FROM t_aircraft_type LIMIT 1", UUID.class);
    }

    private static String uniqueImmatriculation() {
        return "HB-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private UUID seedAircraft() {
        return aircrafts.save(newAircraft(anyAircraftTypeId(), uniqueImmatriculation()))
                .getId().value();
    }

    private static Aircraft newAircraft(UUID aircraftTypeId, String immatriculation) {
        return Aircraft.register(SEED_CLUB, SEED_CLUB, aircraftTypeId, immatriculation,
                null, null, null, null, null, null, null, null, null, 2,
                null, null, null, null, null, false, false, false, false, null, null);
    }
}
