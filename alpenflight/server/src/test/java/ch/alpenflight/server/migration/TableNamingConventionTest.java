package ch.alpenflight.server.migration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.server.testsupport.PostgresTestContainerLifecycle;
import ch.alpenflight.server.testsupport.SharedPostgresContainer;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ClassUtils;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIf(value = "ch.alpenflight.server.testsupport.SharedPostgresContainer#available",
        disabledReason = "Docker unavailable — start Docker Desktop / Docker Engine to run integration tests")
class TableNamingConventionTest {

    private static final Set<String> EXCEPTIONS = Set.of("flyway_schema_history");

    private static final PostgresTestContainerLifecycle POSTGRES = SharedPostgresContainer.INSTANCE;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::jdbcUrl);
        r.add("spring.datasource.username", POSTGRES::username);
        r.add("spring.datasource.password", POSTGRES::password);
        r.add("spring.flyway.url", POSTGRES::jdbcUrl);
        r.add("spring.flyway.user", POSTGRES::username);
        r.add("spring.flyway.password", POSTGRES::password);
    }

    @Autowired DataSource dataSource;

    @Test
    void every_base_table_in_public_starts_with_t_prefix() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.createStatement().executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = 'public' "
                                + "AND table_type = 'BASE TABLE'")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (EXCEPTIONS.contains(name)) {
                    continue;
                }
                if (!name.startsWith("t_")) {
                    offenders.add(name);
                }
            }
        }
        assertThat(offenders)
                .as("Every public BASE TABLE must carry the t_ prefix "
                        + "(exceptions: %s). Add the prefix in the migration "
                        + "or extend EXCEPTIONS with an inline rationale.", EXCEPTIONS)
                .isEmpty();
    }

    @Test
    void every_entity_resolved_table_name_starts_with_t_prefix() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> entity : discoverEntities()) {
            Table annotation = entity.getAnnotation(Table.class);
            String name = annotation == null || annotation.name().isEmpty()
                    ? toSnakeCase(entity.getSimpleName())
                    : annotation.name();
            if (!name.startsWith("t_")) {
                offenders.add(entity.getName() + " -> " + name);
            }
        }
        assertThat(offenders)
                .as("Every @Entity must resolve to a t_-prefixed @Table(name=…). "
                        + "Implicit naming-strategy fall-through is not allowed — add an "
                        + "explicit @Table annotation.")
                .isEmpty();
    }

    private static List<Class<?>> discoverEntities() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                        return beanDefinition.getMetadata().isIndependent();
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        Set<Class<?>> out = new LinkedHashSet<>();
        for (var bd : scanner.findCandidateComponents("ch.alpenflight")) {
            String name = bd.getBeanClassName();
            if (name == null) {
                continue;
            }
            try {
                out.add(ClassUtils.forName(name, TableNamingConventionTest.class.getClassLoader()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Cannot load discovered @Entity " + name, e);
            }
        }
        return new ArrayList<>(out);
    }

    private static String toSnakeCase(String camel) {
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                out.append('_');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }
}
