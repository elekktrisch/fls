package ch.alpenflight.migration.bundle;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.identity.CountryMapper;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Module-level structural rules. The declarative rules are pure ArchUnit;
 * the EntityType ingest-order rule walks {@code Mapper.foreignKeys()} at
 * runtime, so it lives as a JUnit test on the same class — same enforcement
 * window, one file to read.
 */
@AnalyzeClasses(
        packages = "ch.alpenflight.migration.bundle",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    @ArchTest
    static final ArchRule mustNotDependOnServerRepositoriesOrPerson =
            noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "ch.alpenflight.server.person..",
                            "ch.alpenflight.server..repository..")
                    .orShould()
                    .dependOnClassesThat()
                    .areAnnotatedWith("org.springframework.stereotype.Repository")
                    .because("migration-bundle is a standalone library — no Spring / "
                            + "server runtime dependency. S-141 wires the JDBC / "
                            + "PreparedStatement at the call site.")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule mustNotTouchDiskSinks =
            noClasses()
                    .should()
                    .callMethod(Files.class, "createTempFile",
                            java.nio.file.Path.class, String.class, String.class,
                            java.nio.file.attribute.FileAttribute[].class)
                    .orShould()
                    .callMethod(Files.class, "createTempFile",
                            String.class, String.class,
                            java.nio.file.attribute.FileAttribute[].class)
                    .orShould()
                    .callMethod(File.class, "createTempFile",
                            String.class, String.class)
                    .orShould()
                    .callMethod(File.class, "createTempFile",
                            String.class, String.class, File.class)
                    .orShould()
                    .dependOnClassesThat()
                    .areAssignableTo(FileOutputStream.class)
                    .orShould()
                    .dependOnClassesThat()
                    .areAssignableTo(FileWriter.class)
                    .orShould()
                    .dependOnClassesThat()
                    .areAssignableTo(RandomAccessFile.class)
                    .orShould()
                    .dependOnClassesThat()
                    .areAssignableTo(FileChannel.class)
                    .because("Plaintext bundle bytes must never hit local disk. "
                            + "readEntity operates on in-memory JsonNode; LegacyIdMapWriter "
                            + "is wired directly to PgConnection.getCopyAPI() by S-141. "
                            + "Files.write* / Files.newOutputStream / FileWriter / FileChannel / "
                            + "RandomAccessFile all qualify as writable disk sinks.")
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule mustNotUseStringStatementOrNativeQueryConcat =
            noClasses()
                    .should()
                    .callMethod(Statement.class, "executeQuery", String.class)
                    .orShould()
                    .callMethod(Statement.class, "execute", String.class)
                    .orShould()
                    .callMethod(Statement.class, "executeUpdate", String.class)
                    .orShould()
                    .callMethodWhere(
                            com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                    com.tngtech.archunit.core.domain.properties.HasName
                                            .Predicates.name("createNativeQuery")))
                    .because("Per ADR 0022 D2 + parity discipline: no string-concat SQL "
                            + "in the mapper hot path. PreparedStatement is the only "
                            + "supported bind surface; createNativeQuery would smuggle a "
                            + "JPA dependency the library refuses by design.")
                    .allowEmptyShould(true);

    /**
     * Concrete-mapper registry the ingest-order check walks. Each follow-up
     * (S-184 / S-185 / S-186) appends its mappers as it lands. Hard-coded
     * rather than reflective so the diff is reviewable;
     * {@link #knownMappersListCoversEveryConcreteMapperOnTheClasspath()}
     * fails loudly if a follow-up forgets to register one.
     */
    private static final List<Mapper> KNOWN_MAPPERS = List.of(
            new CountryMapper());

    @Test
    void everyMapperForeignKeyTargetPrecedesSelfInEntityTypeOrder() {
        for (Mapper mapper : KNOWN_MAPPERS) {
            int selfOrdinal = mapper.entityType().ordinal();
            for (EntityType target : mapper.foreignKeys()) {
                assertThat(target.ordinal())
                        .as("%s declares FK to %s but %s.ordinal() >= %s.ordinal() — "
                                + "ingest cannot resolve the FK target before the source. "
                                + "Reorder EntityType so FK targets precede sources.",
                                mapper.getClass().getSimpleName(),
                                target, target, mapper.entityType())
                        .isLessThan(selfOrdinal);
            }
        }
    }

    @Test
    void knownMappersListCoversEveryConcreteMapperOnTheClasspath() {
        var bundleClasses = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("ch.alpenflight.migration.bundle");
        var concreteMapperFqns = bundleClasses.stream()
                .filter(javaClass -> javaClass.isAssignableTo(Mapper.class))
                .filter(javaClass -> !javaClass.isInterface())
                .filter(javaClass -> !javaClass.getModifiers().contains(
                        com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT))
                .map(javaClass -> javaClass.getFullName())
                .toList();
        var registered = KNOWN_MAPPERS.stream()
                .map(mapper -> mapper.getClass().getName())
                .toList();
        assertThat(registered)
                .as("KNOWN_MAPPERS is the hand-curated list the ingest-order rule walks. "
                        + "A new concrete Mapper on the classpath must be registered here "
                        + "(or the ingest-order rule silently skips it).")
                .containsAll(concreteMapperFqns);
    }
}
