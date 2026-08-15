package ch.alpenflight.legacyextract.tenant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record TenantClassificationRecord(
        String legacyTable,
        String targetEntity,
        TenantScope legacyScope,
        TenantScope targetScope,
        String tenantColumn,
        String rationaleRef,
        @JsonProperty("via") String indirectTenantFkChain,
        List<String> preconditions,
        boolean piiBlob,
        boolean emitsAudit,
        List<String> rideThroughTargets,
        String tenancyEnforcement) {}
