package ch.alpenflight.audit.application;

import ch.alpenflight.audit.application.AuditEventDtos.AuditEventPage;
import ch.alpenflight.audit.application.AuditEventDtos.AuditEventQuery;
import ch.alpenflight.audit.application.AuditEventDtos.AuditEventRow;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.audit.domain.MutationAuditEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Application-layer query surface for {@code mutation_audit_event}. Sits
 * between the {@code @RestController} and the
 * {@link MutationAuditEventRepository} port so the web layer stays thin
 * (presentation only) and the row → DTO translation is co-located with
 * the other audit-trail application services. Matches the
 * {@code web → service → port} layering convention the rest of the codebase
 * follows.
 *
 * <p>Reads pass through Hibernate's {@code @TenantId} discriminator on
 * the entity, so per-tenant scoping is structural — no per-query
 * predicate.
 */
@Service
public class AuditQueryService {

    private final MutationAuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public AuditQueryService(MutationAuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public AuditEventPage findPage(AuditEventQuery query) {
        List<MutationAuditEvent> rows = repository.findPage(
                query.occurredFrom(),
                query.occurredTo(),
                query.action(),
                query.targetEntityType(),
                query.actorUserId(),
                query.pageSize(),
                query.pageOffset());
        List<AuditEventRow> items = rows.stream().map(this::toRow).toList();
        // Cursor-pagination proxy: if we got the full page back, *probably*
        // more remain. The caller's next request may legitimately come back
        // empty when total exactly matches pageSize — well-known tradeoff
        // for skipping a separate count query.
        boolean hasMore = items.size() >= query.pageSize();
        Integer nextOffset = hasMore ? query.pageOffset() + items.size() : null;
        return new AuditEventPage(items, hasMore, nextOffset);
    }

    private AuditEventRow toRow(MutationAuditEvent e) {
        UUID id = Objects.requireNonNull(e.getId(),
                "audit row missing required id");
        Instant occurredAt = Objects.requireNonNull(e.getOccurredAt(),
                "audit row missing required occurredAt");
        AuditAction action = Objects.requireNonNull(e.getAction(),
                "audit row missing required action");
        String type = Objects.requireNonNull(e.getTargetEntityType(),
                "audit row missing required targetEntityType");
        Short status = e.getHttpStatus();
        return new AuditEventRow(
                id,
                occurredAt,
                e.getActorUserId(),
                e.getActorKeycloakSub(),
                e.getTenantClubId(),
                action,
                type,
                e.getTargetEntityId(),
                e.getRequestId(),
                parseJsonOrSentinel(e.getBeforeState()),
                parseJsonOrSentinel(e.getAfterState()),
                e.isFailed(),
                e.isSystemActor(),
                status == null ? null : status.intValue(),
                e.getFailureReason());
    }

    /**
     * Parse the jsonb column into a property map. Returning a map (vs the
     * raw string) means OpenAPI codegen types {@code beforeState}/
     * {@code afterState} as free-form JSON objects, not literal strings —
     * S-056 consumes them as structured payload. Malformed JSON downgrades
     * to a sentinel map entry so a single bad row doesn't fail the whole
     * list.
     */
    private @Nullable Map<String, Object> parseJsonOrSentinel(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JacksonException ex) {
            return Map.of("_audit", "[malformed-json]");
        }
    }

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
}
