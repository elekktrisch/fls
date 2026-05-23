package ch.alpenflight.server.testsupport;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.hibernate.annotations.TenantId;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Reflective discovery of {@code @TenantId}-bearing entity classes under the
 * {@code ch.alpenflight} root package. Used by the S-024 leakage sweep to
 * parameterize over every tenant-scoped JPA entity without a hardcoded
 * roster — adding a new entity = stamp {@code @TenantId} + register a row
 * builder (see {@link TenantScopedRowBuilders}); the sweep auto-includes.
 *
 * <p>Pure classpath scan (Spring's {@link ClassPathScanningCandidateComponentProvider}).
 * Not Spring-context-dependent; can be invoked from plain JUnit tests.
 *
 * <p>Convention for the resolved table name: {@code @Table(name=...)} when
 * present; otherwise lowercase + snake_case of the simple class name
 * (e.g. {@code MemberState} → {@code member_state}). S-024 boot-time
 * assertion catches drift between the convention and {@code @Table} overrides.
 */
public final class TenantScopedEntityCatalog {

    private TenantScopedEntityCatalog() {}

    /** Returns the @TenantId-bearing entity classes, sorted by FQN for stable iteration. */
    public static List<Class<?>> discoverTenantScopedEntities() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        // Entities aren't @Component-stereotyped; widen the default
                        // accept-rule to "anything with a class name". The
                        // AnnotationTypeFilter below already gates on @Entity.
                        return beanDefinition.getMetadata().isIndependent();
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<Class<?>> out = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("ch.alpenflight")) {
            String name = bd.getBeanClassName();
            if (name == null) {
                continue;
            }
            Class<?> clazz;
            try {
                clazz = ClassUtils.forName(name, TenantScopedEntityCatalog.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Cannot load discovered @Entity " + name, e);
            }
            if (hasTenantIdField(clazz)) {
                out.add(clazz);
            }
        }
        out.sort(Comparator.comparing(Class::getName));
        return List.copyOf(out);
    }

    /** Returns the resolved table name for an entity ({@code @Table(name=…)} or snake_case). */
    public static String resolveTableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table != null && !table.name().isEmpty()) {
            return table.name();
        }
        return camelToSnake(entityClass.getSimpleName());
    }

    private static boolean hasTenantIdField(Class<?> clazz) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.isAnnotationPresent(TenantId.class)) {
                return true;
            }
        }
        return false;
    }

    private static String camelToSnake(String camel) {
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                out.append('_');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }
}
