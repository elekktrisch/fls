package ch.alpenflight.audit.application;

import ch.alpenflight.audit.application.AuditRedactionProperties.EntityPolicy;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Component
class AuditRedactionConfigStartupGuard implements InitializingBean {

    static final String CONFIG_ROOT = "audit.redaction";
    static final String APPLICATION_CLASS_RESOURCE_PATTERN =
            ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + "ch/alpenflight/**/*.class";

    static final String RESIDUAL_LIMIT_THE_GUARD_DOES_NOT_COVER =
            "Residual limit: this guard proves each configured name is an instance field of the "
                    + "declared snapshot type. It does not prove that every AuditedTarget call site "
                    + "passes an instance of that type, and it does not read the field values.";

    private final AuditRedactionProperties properties;
    private final Environment environment;
    private final ResourcePatternResolver applicationClasses;

    AuditRedactionConfigStartupGuard(AuditRedactionProperties properties,
                                     Environment environment,
                                     ResourcePatternResolver applicationClasses) {
        this.properties = properties;
        this.environment = environment;
        this.applicationClasses = applicationClasses;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> violations = violations();
        if (!violations.isEmpty()) {
            throw new IllegalStateException(refusalMessage(violations));
        }
    }

    static String refusalMessage(List<String> violations) {
        StringBuilder message = new StringBuilder(
                "The audit redaction configuration names " + violations.size()
                        + " thing(s) that do not exist. The application refuses to start, because a "
                        + "name that resolves to nothing silently empties or silently widens the "
                        + "audit trail.\n");
        for (String violation : violations) {
            message.append("  - ").append(violation).append('\n');
        }
        message.append(RESIDUAL_LIMIT_THE_GUARD_DOES_NOT_COVER);
        return message.toString();
    }

    List<String> violations() {
        Map<String, Set<String>> classNamesBySimpleName = indexApplicationClassesBySimpleName();
        List<String> violations = new ArrayList<>();

        for (String declared : entityNamesDeclaredInTheConfiguration()) {
            if (!properties.entities().containsKey(declared)) {
                violations.add(declared + ": " + CONFIG_ROOT + ".entities." + declared
                        + " — the configuration declares this entity, and the bound policy has no "
                        + "entry for it. The redactor would fall back to full deny and empty the "
                        + "audit trail for it.");
            }
        }

        List<String> denyAll = properties.denyAll();
        for (int i = 0; i < denyAll.size(); i++) {
            String entityName = denyAll.get(i);
            String key = CONFIG_ROOT + ".deny-all[" + i + "]";
            resolveSnapshotTypes(entityName, List.of(), key, classNamesBySimpleName, violations);
        }

        for (Map.Entry<String, EntityPolicy> entry : sortedByEntityName(properties.entities())) {
            String entityName = entry.getKey();
            EntityPolicy policy = entry.getValue();
            String entityKey = CONFIG_ROOT + ".entities." + entityName;
            List<Class<?>> snapshotTypes = resolveSnapshotTypes(
                    entityName, policy.snapshotTypes(), entityKey, classNamesBySimpleName, violations);
            if (snapshotTypes.isEmpty()) {
                continue;
            }
            Set<String> fieldNames = instanceFieldNamesOf(snapshotTypes);
            List<String> allow = policy.allow();
            for (int i = 0; i < allow.size(); i++) {
                String configuredName = allow.get(i);
                String allowKey = entityKey + ".allow[" + i + "]";
                String rejection = rejectionFor(configuredName, fieldNames, snapshotTypes);
                if (rejection != null) {
                    violations.add(entityName + ": " + allowKey + " — " + rejection);
                }
            }
        }
        return violations;
    }

    private Set<String> entityNamesDeclaredInTheConfiguration() {
        ConfigurationPropertyName entitiesPrefix =
                ConfigurationPropertyName.of(CONFIG_ROOT + ".entities");
        Set<String> declared = new TreeSet<>();
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (!(source instanceof IterableConfigurationPropertySource iterable)) {
                continue;
            }
            for (ConfigurationPropertyName name : iterable) {
                if (name.getNumberOfElements() > entitiesPrefix.getNumberOfElements()
                        && entitiesPrefix.isAncestorOf(name)) {
                    declared.add(name.getElement(entitiesPrefix.getNumberOfElements(),
                            ConfigurationPropertyName.Form.ORIGINAL));
                }
            }
        }
        return declared;
    }

    private static List<Map.Entry<String, EntityPolicy>> sortedByEntityName(
            Map<String, EntityPolicy> entities) {
        return List.copyOf(new TreeMap<>(entities).entrySet());
    }

    private static @Nullable String rejectionFor(String configuredName,
                                                 Set<String> fieldNames,
                                                 List<Class<?>> snapshotTypes) {
        if (configuredName == null || configuredName.isBlank()) {
            return "a blank field name is configured";
        }
        if (configuredName.indexOf('.') >= 0) {
            return "\"" + configuredName + "\" is a dotted path, and the redactor matches flat field "
                    + "names only. It recurses into a nested object by allow-listing the holding "
                    + "field and giving the nested type its own entities entry.";
        }
        if (!fieldNames.contains(configuredName)) {
            return "\"" + configuredName + "\" is not an instance field of "
                    + describe(snapshotTypes) + " (superclasses included)";
        }
        return null;
    }

    private List<Class<?>> resolveSnapshotTypes(String entityName,
                                                List<String> declaredSnapshotTypes,
                                                String configKey,
                                                Map<String, Set<String>> classNamesBySimpleName,
                                                List<String> violations) {
        if (!declaredSnapshotTypes.isEmpty()) {
            return loadDeclared(entityName, declaredSnapshotTypes, configKey, violations);
        }
        Set<String> candidates = classNamesBySimpleName.getOrDefault(entityName, Set.of());
        if (candidates.isEmpty()) {
            violations.add(entityName + ": " + configKey + " — the entity name resolves to no class "
                    + "under ch.alpenflight. Rename the key, or declare "
                    + configKey + ".snapshot-types with the fully qualified snapshot class.");
            return List.of();
        }
        if (candidates.size() > 1) {
            violations.add(entityName + ": " + configKey + " — the entity name is ambiguous and "
                    + "matches " + candidates + ". Declare " + configKey
                    + ".snapshot-types with the fully qualified snapshot class.");
            return List.of();
        }
        return loadDeclared(entityName, List.copyOf(candidates), configKey, violations);
    }

    private List<Class<?>> loadDeclared(String entityName,
                                        List<String> classNames,
                                        String configKey,
                                        List<String> violations) {
        List<Class<?>> loaded = new ArrayList<>(classNames.size());
        for (String className : classNames) {
            try {
                loaded.add(ClassUtils.forName(className, applicationClasses.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError e) {
                violations.add(entityName + ": " + configKey + ".snapshot-types — \"" + className
                        + "\" is not a loadable class (" + e.getClass().getSimpleName() + ")");
            }
        }
        return loaded.size() == classNames.size() ? loaded : List.of();
    }

    private static Set<String> instanceFieldNamesOf(List<Class<?>> snapshotTypes) {
        Set<String> names = new LinkedHashSet<>();
        for (Class<?> type : snapshotTypes) {
            for (Field field : PiiRedactor.instanceFieldsThisRedactorSerializes(type)) {
                names.add(field.getName());
            }
        }
        return names;
    }

    private static String describe(List<Class<?>> snapshotTypes) {
        return snapshotTypes.stream().map(Class::getName).reduce((a, b) -> a + " or " + b).orElse("");
    }

    private Map<String, Set<String>> indexApplicationClassesBySimpleName() {
        Map<String, Set<String>> index = new LinkedHashMap<>();
        MetadataReaderFactory metadata = new CachingMetadataReaderFactory(applicationClasses);
        try {
            Resource[] classResources =
                    applicationClasses.getResources(APPLICATION_CLASS_RESOURCE_PATTERN);
            for (Resource resource : classResources) {
                String className = metadata.getMetadataReader(resource).getClassMetadata().getClassName();
                index.computeIfAbsent(simpleNameOf(className), _ -> new LinkedHashSet<>())
                        .add(className);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "The audit redaction guard cannot scan " + APPLICATION_CLASS_RESOURCE_PATTERN
                            + ", so it cannot prove the configured names resolve. It refuses to let "
                            + "the application start with an unverified audit redaction policy.", e);
        }
        return index;
    }

    private static String simpleNameOf(String className) {
        int lastSeparator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        return className.substring(lastSeparator + 1);
    }
}
