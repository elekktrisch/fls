package ch.alpenflight.migration.bundle;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ParityMarkers {

    private ParityMarkers() { }

    public static Set<String> ignored(Class<? extends Mapper> mapperClass) {
        return columnsAnnotatedWith(mapperClass, ParityIgnore.class);
    }

    public static Set<String> sentinels(Class<? extends Mapper> mapperClass) {
        return columnsAnnotatedWith(mapperClass, ParitySentinel.class);
    }

    private static Set<String> columnsAnnotatedWith(
            Class<? extends Mapper> mapperClass,
            Class<? extends Annotation> marker) {
        Set<String> result = new LinkedHashSet<>();
        for (Field field : mapperClass.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) {
                continue;
            }
            if (field.getType() != String.class) {
                continue;
            }
            if (field.getAnnotation(marker) == null) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof String columnName) {
                    result.add(columnName);
                }
            } catch (IllegalAccessException unreachable) {
                throw new IllegalStateException(
                        "static final String field made inaccessible: " + field, unreachable);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
