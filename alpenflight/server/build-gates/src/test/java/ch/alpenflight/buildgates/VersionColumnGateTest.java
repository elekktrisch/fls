package ch.alpenflight.buildgates;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.core.club.Club;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Version;
import org.junit.jupiter.api.Test;

class VersionColumnGateTest {

    private static final ArchCondition<JavaClass> CARRY_EXACTLY_ONE_VERSION_FIELD =
            new ArchCondition<>("carry exactly one @Version field") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    long versionFieldCount = javaClass.getAllFields().stream()
                            .filter(field -> field.isAnnotatedWith(Version.class))
                            .count();
                    if (versionFieldCount != 1) {
                        events.add(SimpleConditionEvent.violated(
                                javaClass,
                                javaClass.getFullName() + " has " + versionFieldCount
                                        + " @Version field(s), expected exactly one"));
                    }
                }
            };

    @Test
    void productionEntitiesEachCarryExactlyOneVersionField() {
        JavaClasses classes = new ClassFileImporter().importPackages(
                "ch.alpenflight.platform", "ch.alpenflight.core", "ch.alpenflight.modulesopen", "ch.alpenflight.modulespro");

        assertThat(classes.contain(Club.class))
                .as("Gate 9 (version column) production scan must include its existing pass-case, Club")
                .isTrue();
        assertThatNoException().as("Gate 9 (version column)").isThrownBy(() -> rule().check(classes));
    }

    @Test
    void fixtureEntityWithoutVersionFieldIsCaught() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("ch.alpenflight.buildgates.versioncolumngate.fixtures.missingversion");

        assertThatThrownBy(() -> rule().check(classes)).hasMessageContaining("Gate 9");
    }

    @Test
    void fixtureEntityWithInheritedVersionFieldIsNotFlagged() {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("ch.alpenflight.buildgates.versioncolumngate.fixtures.inheritedversion");

        assertThatNoException()
                .as("Gate 9 (version column) must not flag a @Version field inherited from a "
                        + "@MappedSuperclass")
                .isThrownBy(() -> rule().check(classes));
    }

    private static ArchRule rule() {
        return classes()
                .that()
                .areAnnotatedWith(Entity.class)
                .should(CARRY_EXACTLY_ONE_VERSION_FIELD)
                .as("Gate 9 (version column): every @Entity class carries exactly one @Version field");
    }
}
