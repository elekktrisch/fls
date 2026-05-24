package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.AlpenFlightApplication;
import ch.alpenflight.audit.domain.AuditRedact;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * S-027 coverage guard. Every {@code @Entity} that the audit serializer
 * can encounter must have an explicit redaction decision for every field:
 *
 * <ul>
 *   <li>Listed in the entity's allow-list in {@code audit.redaction.entities.<Name>.allow}, OR</li>
 *   <li>The entity itself is in {@code audit.redaction.deny-all} (all fields redacted), OR</li>
 *   <li>The field carries the {@link AuditRedact} annotation.</li>
 * </ul>
 *
 * <p>A new entity field added without one of those three decisions
 * (deliberate "this is safe to log" or "this is PII") fails the build.
 * The PII default-deny serializer would still emit {@code "[redacted]"}
 * at runtime, but the lapse hides the question of intent — the test
 * forces the conversation in the same PR.
 *
 * <p>The {@code mutation_audit_event} entity itself is exempt: it is the
 * audit ledger and is never serialised into another audit row. Reference-
 * data entities (cross-tenant catalog) are also exempt — they hold no
 * tenant data so cannot leak PII through tenant-scoped audit rows.
 */
class AuditRedactionCoverageTest {

    /** Packages whose {@code @Entity}s are tenant-scoped and serialise into audit rows. */
    private static final Set<String> AUDITED_PACKAGE_ROOTS = Set.of(
            "ch.alpenflight.aircraft.domain",
            "ch.alpenflight.clubs.domain",
            "ch.alpenflight.flights.domain",
            "ch.alpenflight.flighttypes.domain",
            "ch.alpenflight.locations.domain",
            "ch.alpenflight.persons.domain"
    );

    /** Entity classes excluded because they're not subject to audit serialisation. */
    private static final Set<Class<?>> EXEMPT = Set.of(
            MutationAuditEvent.class
    );

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .importPackagesOf(AlpenFlightApplication.class);

    @Test
    void every_audited_entity_field_has_an_explicit_redaction_decision() throws Exception {
        RedactionPolicy policy = loadPolicy();

        List<Class<?>> audited = new ArrayList<>();
        classes.stream()
                .filter(c -> c.isAnnotatedWith(Entity.class))
                .filter(c -> AUDITED_PACKAGE_ROOTS.stream()
                        .anyMatch(pkg -> c.getPackageName().equals(pkg)
                                || c.getPackageName().startsWith(pkg + ".")))
                .forEach(c -> {
                    try {
                        Class<?> resolved = Class.forName(c.getFullName());
                        if (!EXEMPT.contains(resolved)) {
                            audited.add(resolved);
                        }
                    } catch (ClassNotFoundException e) {
                        throw new AssertionError("Cannot resolve entity " + c.getFullName(), e);
                    }
                });

        assertThat(audited)
                .as("Sanity: the scan should find at least one audited entity")
                .isNotEmpty();

        List<String> uncoveredFields = new ArrayList<>();
        for (Class<?> entity : audited) {
            String entityName = simpleName(entity);
            boolean denyAll = policy.denyAll.contains(entityName);
            Set<String> allow = policy.allowed(entityName);
            for (Field f : collectInstanceFields(entity)) {
                if (denyAll) {
                    continue;
                }
                if (f.isAnnotationPresent(AuditRedact.class)) {
                    continue;
                }
                if (allow.contains(f.getName())) {
                    continue;
                }
                uncoveredFields.add(entityName + "." + f.getName());
            }
        }

        assertThat(uncoveredFields)
                .as("Every audited @Entity field must have an explicit redaction decision — "
                        + "either added to application.yml audit.redaction allow-list, added to deny-all, "
                        + "or annotated @AuditRedact. Uncovered: %s", uncoveredFields)
                .isEmpty();
    }

    private static List<Field> collectInstanceFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        Class<?> cur = type;
        while (cur != null && cur != Object.class) {
            for (Field f : cur.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (f.isSynthetic()) continue;
                out.add(f);
            }
            cur = cur.getSuperclass();
        }
        return out;
    }

    private static String simpleName(Class<?> cls) {
        // Logical entity name — defaults to the simple class name unless overridden
        // by @Entity(name=...). The redaction policy keys on the logical name.
        Entity e = cls.getAnnotation(Entity.class);
        if (e != null && !e.name().isEmpty()) {
            return e.name();
        }
        return cls.getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static RedactionPolicy loadPolicy() throws Exception {
        try (InputStream in = AuditRedactionCoverageTest.class
                .getResourceAsStream("/application.yml")) {
            if (in == null) {
                throw new AssertionError("application.yml not on classpath");
            }
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> audit = (Map<String, Object>) root.getOrDefault("audit", Map.of());
            Map<String, Object> redaction = (Map<String, Object>) audit.getOrDefault("redaction", Map.of());
            List<String> denyAll = (List<String>) redaction.getOrDefault("deny-all", List.of());
            Map<String, Object> entities = (Map<String, Object>) redaction.getOrDefault("entities", Map.of());

            Map<String, Set<String>> entityAllow = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : entities.entrySet()) {
                Map<String, Object> entry = (Map<String, Object>) e.getValue();
                List<String> allow = (List<String>) entry.getOrDefault("allow", List.of());
                entityAllow.put(e.getKey(), Set.copyOf(allow));
            }
            return new RedactionPolicy(Set.copyOf(denyAll), entityAllow);
        }
    }

    private record RedactionPolicy(Set<String> denyAll, Map<String, Set<String>> allowByEntity) {
        Set<String> allowed(String entityName) {
            return allowByEntity.getOrDefault(entityName, Collections.emptySet());
        }
    }
}
