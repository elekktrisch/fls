package ch.alpenflight.arch;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.util.ClassUtils;

class AggregateInternalRepositoryGuardTest {

    @Test
    void no_jpa_repository_targets_an_aggregate_internal_entity() {
        List<Class<?>> repositories = discoverProductionRepositoryInterfaces();
        List<String> violations = new ArrayList<>();
        for (Class<?> repo : repositories) {
            Class<?> entity = jpaRepositoryEntityArgument(repo);
            if (entity == null) {
                continue;
            }
            if (isAggregateInternal(entity)) {
                violations.add(repo.getName() + " → " + entity.getName());
            }
        }
        assertThat(violations)
                .as("JpaRepository targeting an aggregate-internal entity bypasses the "
                        + "parent aggregate's @TenantId discriminator (ADR 0018 + S-024)")
                .isEmpty();
    }

    @Test
    void at_least_one_aggregate_internal_entity_exists_on_classpath() {
        List<Class<?>> entities = discoverProductionEntities();
        long internal = entities.stream().filter(this::isAggregateInternal).count();
        assertThat(internal)
                .as("Expected at least one aggregate-internal entity on the classpath; "
                        + "if zero, the guard above is vacuous and the test below has "
                        + "lost its load-bearing companion")
                .isGreaterThanOrEqualTo(1);
    }

    private boolean isAggregateInternal(Class<?> entity) {
        if (!entity.isAnnotationPresent(Entity.class)) return false;
        if (hasTenantIdField(entity)) return false;
        for (Field f : entity.getDeclaredFields()) {
            if (!f.isAnnotationPresent(ManyToOne.class)) continue;
            Class<?> referenced = f.getType();
            if (hasTenantIdField(referenced)) return true;
        }
        return false;
    }

    private static boolean hasTenantIdField(Class<?> clazz) {
        for (Field f : clazz.getDeclaredFields()) {
            if (f.isAnnotationPresent(TenantId.class)) return true;
        }
        return false;
    }

    private static Class<?> jpaRepositoryEntityArgument(Class<?> repo) {
        for (Type genericIface : repo.getGenericInterfaces()) {
            if (!(genericIface instanceof ParameterizedType parameterized)) continue;
            Type raw = parameterized.getRawType();
            if (!(raw instanceof Class<?> rawClass)) continue;
            if (!JpaRepository.class.isAssignableFrom(rawClass)) continue;
            Type[] args = parameterized.getActualTypeArguments();
            if (args.length >= 1 && args[0] instanceof Class<?> entityClass) {
                return entityClass;
            }
        }
        return null;
    }

    private static List<Class<?>> discoverProductionRepositoryInterfaces() {
        ClassPathScanningCandidateComponentProvider scanner = candidateScanner();
        scanner.addIncludeFilter(new AssignableTypeFilter(JpaRepository.class));
        return loadCandidates(scanner);
    }

    private static List<Class<?>> discoverProductionEntities() {
        ClassPathScanningCandidateComponentProvider scanner = candidateScanner();
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(Entity.class));
        return loadCandidates(scanner);
    }

    private static ClassPathScanningCandidateComponentProvider candidateScanner() {
        return new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
                return beanDefinition.getMetadata().isIndependent();
            }
        };
    }

    private static List<Class<?>> loadCandidates(ClassPathScanningCandidateComponentProvider scanner) {
        List<Class<?>> out = new ArrayList<>();
        for (var bd : scanner.findCandidateComponents("ch.alpenflight")) {
            String name = bd.getBeanClassName();
            if (name == null) continue;
            try {
                out.add(ClassUtils.forName(name, AggregateInternalRepositoryGuardTest.class.getClassLoader()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Cannot load " + name, e);
            }
        }
        return out;
    }
}
