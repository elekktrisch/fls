package ch.alpenflight.audit.application;

import ch.alpenflight.audit.application.AuditEventDtos.AuditEventPage;
import ch.alpenflight.audit.application.AuditEventDtos.AuditEventQuery;
import ch.alpenflight.audit.application.AuditEventDtos.AuditEventRow;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.audit.domain.MutationAuditEventRepository;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

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
        boolean hasMore = items.size() >= query.pageSize();
        Integer nextOffset = hasMore ? query.pageOffset() + items.size() : null;
        return new AuditEventPage(items, hasMore, nextOffset);
    }

    private AuditEventRow toRow(MutationAuditEvent e) {
        Short status = e.getHttpStatus();
        return new AuditEventRow(
                e.getId(),
                e.getOccurredAt(),
                e.getActorUserId(),
                e.getActorKeycloakSub(),
                e.getTenantClubId(),
                e.getAction(),
                e.getTargetEntityType(),
                e.getTargetEntityId(),
                e.getRequestId(),
                parseJsonOrSentinel(e.getBeforeState()),
                parseJsonOrSentinel(e.getAfterState()),
                e.isFailed(),
                e.isSystemActor(),
                status == null ? null : status.intValue(),
                e.getFailureReason());
    }

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
