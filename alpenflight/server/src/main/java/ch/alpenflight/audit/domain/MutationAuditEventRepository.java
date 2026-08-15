package ch.alpenflight.audit.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public interface MutationAuditEventRepository {

    MutationAuditEvent append(MutationAuditEvent event);

    Optional<MutationAuditEvent> findById(UUID id);

    List<MutationAuditEvent> findPage(@Nullable Instant occurredFrom,
                                      @Nullable Instant occurredTo,
                                      @Nullable AuditAction action,
                                      @Nullable String targetEntityType,
                                      @Nullable UUID actorUserId,
                                      int pageSize,
                                      int pageOffset);
}
