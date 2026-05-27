package ch.alpenflight.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Project-wide naming rules. Build-time guard that complements the runtime
 * {@code TableNamingConventionTest} — fails the build immediately if a new
 * {@code @Entity} class lands without an explicit {@code @Table(name =
 * "t_…")} annotation. Cheaper to fail in ArchUnit than to wait for a
 * Postgres-backed IT to surface the same problem.
 */
@AnalyzeClasses(
        packages = "ch.alpenflight",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class NamingRulesTest {

    /**
     * Every {@code @Entity} must declare an explicit {@code @Table(name =
     * "t_…")}. Hibernate's implicit naming-strategy fall-through is
     * forbidden — the SQL identifier should be greppable from the Java
     * source.
     */
    @ArchTest
    static final ArchRule entities_carry_t_prefixed_table_annotation =
            classes()
                    .that().areAnnotatedWith(Entity.class)
                    .should(haveTablePrefix("t_"))
                    .as("every @Entity must wear @Table(name = \"t_<name>\") "
                            + "— see ADR 0025 (table-naming convention).");

    private static ArchCondition<JavaClass> haveTablePrefix(String prefix) {
        return new ArchCondition<>("have @Table(name = \"" + prefix + "…\")") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                JavaAnnotation<?> annotation = item.getAnnotations().stream()
                        .filter(a -> a.getRawType().getName().equals(Table.class.getName()))
                        .findFirst()
                        .orElse(null);
                if (annotation == null) {
                    events.add(SimpleConditionEvent.violated(item,
                            "@Entity " + item.getName() + " has no explicit @Table annotation"));
                    return;
                }
                Object nameValue = annotation.getProperties().get("name");
                String name = nameValue == null ? "" : nameValue.toString();
                if (name.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(item,
                            "@Entity " + item.getName() + " has @Table without a name= attribute"));
                    return;
                }
                if (!name.startsWith(prefix)) {
                    events.add(SimpleConditionEvent.violated(item,
                            "@Entity " + item.getName() + " resolves to @Table(name = \""
                                    + name + "\"); must start with \"" + prefix + "\""));
                }
            }
        };
    }

}
