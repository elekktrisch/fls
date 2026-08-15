package ch.alpenflight.server.testsupport;

import jakarta.persistence.Column;
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

public final class TenantScopedEntityCatalog {

    private TenantScopedEntityCatalog() {}

    public static List<Class<?>> discoverTenantScopedEntities() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
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

    public static String resolveTableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table != null && !table.name().isEmpty()) {
            return table.name();
        }
        return camelToSnake(entityClass.getSimpleName());
    }

    public static String resolveTenantColumnName(Class<?> entityClass) {
        for (Field f : entityClass.getDeclaredFields()) {
            if (!f.isAnnotationPresent(TenantId.class)) {
                continue;
            }
            Column column = f.getAnnotation(Column.class);
            if (column != null && !column.name().isEmpty()) {
                return column.name();
            }
            return camelToSnake(f.getName());
        }
        throw new IllegalStateException(
                "No @TenantId field on " + entityClass.getName());
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
