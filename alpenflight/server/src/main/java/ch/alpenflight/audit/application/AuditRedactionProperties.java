package ch.alpenflight.audit.application;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("audit.redaction")
public record AuditRedactionProperties(Map<String, EntityPolicy> entities,
                                       List<String> denyAll) {

    public AuditRedactionProperties {
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        denyAll = denyAll == null ? List.of() : List.copyOf(denyAll);
    }

    public record EntityPolicy(List<String> allow, List<String> snapshotTypes) {
        public EntityPolicy {
            allow = allow == null ? List.of() : List.copyOf(allow);
            snapshotTypes = snapshotTypes == null ? List.of() : List.copyOf(snapshotTypes);
        }

        public static EntityPolicy allowing(List<String> allow) {
            return new EntityPolicy(allow, List.of());
        }
    }
}
