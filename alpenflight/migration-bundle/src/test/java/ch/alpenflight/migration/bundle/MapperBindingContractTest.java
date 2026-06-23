package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Generic, registry-wide early-binding contract for EVERY {@link Mapper} the
 * bundle ships ({@link KnownMappers#all()}). Fails FAST at build time
 * ({@code ./gradlew check}) so a journey that carries a migration mapper can no
 * longer reach the ~20-min real fanout with (a) a mapper that has no
 * {@link MapperLegacyBindings} producer entry, or (b) a producer SELECT that has
 * drifted from the legacy columns the mapper actually reads. This is the
 * {@code verify_infra_is_run_not_just_authored} rider, consumed by J-6 T-12 —
 * J-5 T-07 caught a zero-binding only at the fanout; this catches the same class
 * here.
 *
 * <p>Unlike {@link MapperLegacyBindingsTest} (which hand-codes the expected
 * legacy column list per entity, entity-by-entity), this test is fully GENERIC:
 * it walks the registry, so a NEW mapper added to {@link KnownMappers} is guarded
 * automatically and cannot slip through silently.
 *
 * <h2>What the STATIC check covers</h2>
 * <ol>
 *   <li><strong>Binding-presence.</strong> Every registered mapper's
 *       {@link EntityType} either HAS a {@link MapperLegacyBindings} entry or is
 *       explicitly listed in {@link #KNOWN_UNBOUND} (mappers authored ahead of
 *       the journey that wires their binding — the J-0b/J-5 authored-but-unrun
 *       pattern). A mapper that is neither bound nor known-unbound → RED, naming
 *       the mapper + entity type. This makes it impossible to add a new mapper
 *       without consciously either binding it or recording WHY it is still
 *       unbound.</li>
 *   <li><strong>Producer-SELECT ↔ mapper-reads coherence.</strong> For every
 *       BOUND mapper, every legacy ResultSet column its {@code writeNdjson} reads
 *       ({@code source.getXxx("LegacyColumn")}) must be projected by the bound
 *       producer SELECT — else the live export aborts on a non-existent column or
 *       silently reads {@code NULL}. The legacy column names live ONLY as string
 *       literals inside {@code writeNdjson}, so the strongest static source for
 *       them is the mapper's own source file: this test parses each bound
 *       mapper's {@code .java} and asserts the SELECT contains each read column.
 *       (This is the build-time generalisation of the hand-coded per-entity
 *       {@code …SelectProjectsEveryColumnTheMapperReads} cases.)</li>
 *   <li><strong>Consumer-INSERT presence.</strong> A bound FULL_PORT mapper must
 *       carry a non-empty consumer INSERT targeting its declared destination
 *       table; a SYSTEM_GLOBAL binding's consumer half is empty by contract.</li>
 *   <li><strong>Declared columns non-empty.</strong> A bound mapper must declare
 *       at least one {@link Mapper#columns()} entry (a zero-column mapper would
 *       bind nothing on ingest).</li>
 * </ol>
 *
 * <h2>What the static check CANNOT catch — defers to the real fanout</h2>
 * <ul>
 *   <li>Whether a SELECTed legacy column actually EXISTS in the live MSSQL
 *       FLSTest schema (a renamed/dropped column the mapper still reads but the
 *       SELECT also names): the synth bundle uses aliased names, so only the real
 *       nightly fanout validates the producer SELECT against the real schema
 *       ({@code project_synth_bundle_doesnt_validate_producer_select}). This test
 *       proves the SELECT and the mapper AGREE; it cannot prove either matches the
 *       real legacy DDL.</li>
 *   <li>Type-fidelity coercions (e.g. a {@code getInt} over a {@code uniqueidentifier}
 *       legacy column): only the real JDBC round-trip throws.</li>
 *   <li>The reverse direction — a SELECT column the mapper never reads — is NOT
 *       asserted: an over-broad SELECT is benign (the extra column is simply
 *       ignored on the cursor), so flagging it would only add false positives.</li>
 * </ul>
 */
class MapperBindingContractTest {

    /**
     * Mappers AUTHORED but not yet wired into {@link MapperLegacyBindings} —
     * their producer SELECT / round-trip lands in a later journey (the
     * J-0b/J-5 authored-but-unrun pattern, {@code verify_infra_is_run_not_just_authored}).
     * Each entry is a conscious "bound later, here's why", NOT a silent gap:
     * removing the binding without removing the allowlist entry → the
     * binding-presence test stays green (correct — now bound), and adding a
     * NEW unbound mapper without listing it here → RED.
     *
     * <p>Empty this set as the bindings land. The PLANNING_DAY trio was wired by
     * J-6 T-11 — those three are now bound + actively guarded (their entries
     * removed here), and the round-trip is proven by {@code PlanningDayMigrationRoundTripIT}.
     */
    private static final Set<EntityType> KNOWN_UNBOUND = EnumSet.of(
            // --- bound by their own future journeys (not J-6) ---
            EntityType.MEMBER_STATE,
            EntityType.PERSON_CATEGORY,
            EntityType.PERSON_CATEGORY_ASSIGNMENT,
            // ARTICLE bound by J-11 T-08 (operating_club_id CLUB-FK resolution +
            // FROM Articles producer SELECT); round-trip + tenant isolation proven
            // by ArticleMigrationRoundTripIT — the J-10b DeliveryItem RESTRICT done-bar.
            // ACCOUNTING_RULE_FILTER bound by J-8 T-10 (producer SELECT + JSON-blob
            // target extraction + sort-indicator renumber + referenceLookups);
            // round-trip + collision proven by AccountingRuleFilterProducerDedupeIT.
            EntityType.DELIVERY,
            EntityType.DELIVERY_ITEM,
            EntityType.AUDIT_LOG);

    /** Legacy column literals every {@code writeNdjson} reads from the cursor. */
    private static final Pattern LEGACY_READ =
            Pattern.compile("source\\.get\\w+\\(\"([^\"]+)\"");

    @Test
    void everyRegisteredMapperIsEitherBoundOrExplicitlyKnownUnbound() {
        List<String> gaps = new ArrayList<>();
        for (Mapper mapper : KnownMappers.all()) {
            EntityType entity = mapper.entityType();
            boolean bound = MapperLegacyBindings.isRegistered(entity);
            boolean knownUnbound = KNOWN_UNBOUND.contains(entity);
            if (!bound && !knownUnbound) {
                gaps.add(mapper.getClass().getSimpleName() + " (" + entity + ")");
            }
        }
        assertThat(gaps)
                .as("Every Mapper in KnownMappers must have a MapperLegacyBindings "
                        + "producer entry OR be listed in KNOWN_UNBOUND with a reason. "
                        + "The following mappers are NEITHER — this is the J-5 T-07 "
                        + "zero-binding class (caught at build, not at the ~20-min fanout). "
                        + "Bind the mapper in MapperLegacyBindings, or add it to "
                        + "KNOWN_UNBOUND if its binding lands in a later journey: %s", gaps)
                .isEmpty();
    }

    @Test
    void knownUnboundIsHonest_noEntryIsActuallyBound() {
        // Hygiene: KNOWN_UNBOUND must shrink as bindings land. If an entry is in
        // fact bound, the allowlist is stale (e.g. T-11 wired PLANNING_DAY but the
        // entry was not removed) — fail so the pending-set never rots into a
        // silent suppression of a real future gap.
        List<EntityType> staleEntries = KNOWN_UNBOUND.stream()
                .filter(MapperLegacyBindings::isRegistered)
                .toList();
        assertThat(staleEntries)
                .as("These EntityTypes are listed in KNOWN_UNBOUND but ARE now bound — "
                        + "remove them from the pending-set (e.g. once T-11 wires the "
                        + "PlanningDay bindings, drop the PLANNING_DAY* entries): %s",
                        staleEntries)
                .isEmpty();
    }

    @Test
    void everyKnownUnboundEntryIsActuallyAKnownMapper() {
        // Drift guard the other direction: a KNOWN_UNBOUND entry must correspond
        // to a real registered mapper, else it is dead suppression.
        Set<EntityType> registeredEntities = KnownMappers.all().stream()
                .map(Mapper::entityType)
                .collect(java.util.stream.Collectors.toCollection(
                        () -> EnumSet.noneOf(EntityType.class)));
        List<EntityType> orphans = KNOWN_UNBOUND.stream()
                .filter(entity -> !registeredEntities.contains(entity))
                .toList();
        assertThat(orphans)
                .as("KNOWN_UNBOUND lists EntityTypes that no KnownMapper produces — "
                        + "dead suppression, remove them: %s", orphans)
                .isEmpty();
    }

    @Test
    void everyBoundMapperDeclaresAtLeastOneColumn() {
        for (Mapper mapper : KnownMappers.all()) {
            if (!MapperLegacyBindings.isRegistered(mapper.entityType())) {
                continue;
            }
            assertThat(mapper.columns())
                    .as("%s is bound but declares no columns() — ingest would bind "
                            + "nothing", mapper.getClass().getSimpleName())
                    .isNotEmpty();
        }
    }

    @Test
    void everyBoundMappersReadLegacyColumnsAreProjectedByItsProducerSelect() {
        List<String> violations = new ArrayList<>();
        for (Mapper mapper : KnownMappers.all()) {
            EntityType entity = mapper.entityType();
            if (!MapperLegacyBindings.isRegistered(entity)) {
                continue;
            }
            String select = MapperLegacyBindings.selectForProducer(entity)
                    .toUpperCase(Locale.ROOT);
            Set<String> readColumns = legacyColumnsReadBy(mapper);
            assertThat(readColumns)
                    .as("could not statically extract any legacy read-column literal "
                            + "from %s — the source-parse regex drifted; this static "
                            + "check would be hollow", mapper.getClass().getSimpleName())
                    .isNotEmpty();
            for (String legacyColumn : readColumns) {
                if (!select.contains(legacyColumn.toUpperCase(Locale.ROOT))) {
                    violations.add(entity + ": " + mapper.getClass().getSimpleName()
                            + ".writeNdjson reads \"" + legacyColumn + "\" but the bound "
                            + "producer SELECT does not project it");
                }
            }
        }
        assertThat(violations)
                .as("A bound producer SELECT must project every legacy column the "
                        + "mapper reads — else the live export aborts on a non-existent "
                        + "column or silently reads NULL (the producer-SELECT drift "
                        + "class). Caught at build, not at the ~20-min fanout: %s",
                        violations)
                .isEmpty();
    }

    @Test
    void everyBoundFullPortMapperCarriesAConsumerInsertTargetingItsTable() {
        for (Mapper mapper : KnownMappers.all()) {
            EntityType entity = mapper.entityType();
            if (!MapperLegacyBindings.isRegistered(entity)) {
                continue;
            }
            String table = MapperLegacyBindings.newSchemaTable(entity);
            String insert = MapperLegacyBindings.insertForConsumer(entity);
            if (MapperLegacyBindings.portPolicy(entity)
                    == MapperLegacyBindings.PortPolicy.SYSTEM_GLOBAL) {
                assertThat(insert)
                        .as("%s is SYSTEM_GLOBAL — its consumer INSERT is empty by "
                                + "contract (it feeds LegacyIdMapPopulator, not a "
                                + "FULL_PORT INSERT)", entity)
                        .isEmpty();
                continue;
            }
            assertThat(insert)
                    .as("%s is FULL_PORT but carries no consumer INSERT — ingest "
                            + "cannot write the ported row", entity)
                    .isNotEmpty();
            assertThat(insert)
                    .as("%s FULL_PORT consumer INSERT must target its declared "
                            + "destination table %s", entity, table)
                    .contains("INSERT INTO " + table);
        }
    }

    /**
     * Legacy ResultSet column names the mapper's {@code writeNdjson} reads,
     * extracted from the mapper's source file (the only place they exist — the
     * {@link Mapper#columns()} array carries NEW-stack names). Build-time-only:
     * {@code check} always runs against the source tree.
     */
    private static Set<String> legacyColumnsReadBy(Mapper mapper) {
        String source = readMapperSource(mapper.getClass());
        Set<String> columns = new LinkedHashSet<>();
        Matcher matcher = LEGACY_READ.matcher(source);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        return columns;
    }

    private static String readMapperSource(Class<?> mapperClass) {
        Path relative = Path.of("src", "main", "java")
                .resolve(mapperClass.getName().replace('.', '/') + ".java");
        // The test working directory is the module project dir, but be robust to
        // a parent-dir invocation by walking up to the module root.
        for (Path base : candidateModuleRoots()) {
            Path candidate = base.resolve(relative);
            if (Files.isReadable(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(
                            "Failed reading mapper source " + candidate, e);
                }
            }
        }
        throw new IllegalStateException(
                "Could not locate source file for " + mapperClass.getName()
                        + " under any candidate module root — the build-time "
                        + "producer-SELECT coherence check needs the mapper source. "
                        + "Searched relative path: " + relative);
    }

    private static List<Path> candidateModuleRoots() {
        Path cwd = Path.of("").toAbsolutePath();
        List<Path> roots = new ArrayList<>();
        roots.add(cwd);
        roots.add(cwd.resolve("migration-bundle"));
        roots.add(cwd.resolve("alpenflight").resolve("migration-bundle"));
        Path parent = cwd.getParent();
        if (parent != null) {
            roots.add(parent.resolve("migration-bundle"));
        }
        return roots;
    }
}
