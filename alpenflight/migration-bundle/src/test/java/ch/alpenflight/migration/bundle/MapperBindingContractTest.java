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

class MapperBindingContractTest {

    private static final Set<EntityType> KNOWN_UNBOUND = EnumSet.of(
            EntityType.MEMBER_STATE,
            EntityType.PERSON_CATEGORY,
            EntityType.PERSON_CATEGORY_ASSIGNMENT,
            EntityType.AUDIT_LOG);

    private static final Pattern LEGACY_CURSOR_READ_LITERAL =
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

    private static Set<String> legacyColumnsReadBy(Mapper mapper) {
        String source = readMapperSource(mapper.getClass());
        Set<String> columns = new LinkedHashSet<>();
        Matcher matcher = LEGACY_CURSOR_READ_LITERAL.matcher(source);
        while (matcher.find()) {
            columns.add(matcher.group(1));
        }
        return columns;
    }

    private static String readMapperSource(Class<?> mapperClass) {
        Path relative = Path.of("src", "main", "java")
                .resolve(mapperClass.getName().replace('.', '/') + ".java");
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
