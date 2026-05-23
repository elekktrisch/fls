package ch.alpenflight.audit.application;

import ch.alpenflight.audit.domain.AuditAction;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Wire-shapes for the {@link AuditQueryService} surface. Three records:
 *
 * <ul>
 *   <li>{@link AuditEventQuery} — the filter parameters the controller
 *       passes into the service; immutable carrier.</li>
 *   <li>{@link AuditEventRow} — a single row projection. {@code beforeState}
 *       / {@code afterState} are typed as {@link JsonNode} (not raw
 *       strings) so OpenAPI codegen surfaces them as JSON objects in the
 *       generated client.</li>
 *   <li>{@link AuditEventPage} — paginated wrapper with {@code hasMore} /
 *       {@code nextOffset} cursor metadata; the admin UI uses these to
 *       drive infinite-scroll without a separate count query.</li>
 * </ul>
 */
public final class AuditEventDtos {

    private AuditEventDtos() {}

    public record AuditEventQuery(@Nullable Instant occurredFrom,
                                  @Nullable Instant occurredTo,
                                  @Nullable AuditAction action,
                                  @Nullable String targetEntityType,
                                  @Nullable UUID actorUserId,
                                  int pageSize,
                                  int pageOffset) {
    }

    public record AuditEventRow(UUID id,
                                Instant occurredAt,
                                @Nullable UUID actorUserId,
                                @Nullable String actorKeycloakSub,
                                @Nullable UUID tenantClubId,
                                AuditAction action,
                                String targetEntityType,
                                @Nullable UUID targetEntityId,
                                @Nullable String requestId,
                                @Nullable Map<String, Object> beforeState,
                                @Nullable Map<String, Object> afterState,
                                boolean failed,
                                boolean systemActor,
                                @Nullable Integer httpStatus,
                                @Nullable String failureReason) {
    }

    public record AuditEventPage(List<AuditEventRow> items,
                                 boolean hasMore,
                                 @Nullable Integer nextOffset) {
    }
}
