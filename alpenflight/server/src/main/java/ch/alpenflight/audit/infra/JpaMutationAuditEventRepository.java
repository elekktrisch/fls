package ch.alpenflight.audit.infra;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.MutationAuditEvent;
import ch.alpenflight.audit.domain.MutationAuditEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

interface JpaMutationAuditEventRepository extends JpaRepository<MutationAuditEvent, UUID> {

    List<MutationAuditEvent> findByClientIpIsNotNullAndOccurredAtLessThanEqualOrderByOccurredAtAsc(
            Instant cutoff);
}

@Component
class MutationAuditEventRepositoryAdapter implements MutationAuditEventRepository {

    private static final int MAX_PAGE_SIZE = 200;

    private final JpaMutationAuditEventRepository jpa;
    private final EntityManager em;

    MutationAuditEventRepositoryAdapter(JpaMutationAuditEventRepository jpa, EntityManager em) {
        this.jpa = jpa;
        this.em = em;
    }

    @Override
    public MutationAuditEvent append(MutationAuditEvent event) {
        return jpa.save(event);
    }

    @Override
    public MutationAuditEvent saveRedactedRow(MutationAuditEvent redactedRow) {
        return jpa.save(redactedRow);
    }

    @Override
    public Optional<MutationAuditEvent> findById(UUID id) {
        return jpa.findById(id);
    }

    @Override
    public List<MutationAuditEvent> findClientIpBearingRowsOccurredOnOrBefore(Instant cutoff) {
        return jpa.findByClientIpIsNotNullAndOccurredAtLessThanEqualOrderByOccurredAtAsc(cutoff);
    }

    @Override
    public List<MutationAuditEvent> findPage(@Nullable Instant occurredFrom,
                                             @Nullable Instant occurredTo,
                                             @Nullable AuditAction action,
                                             @Nullable String targetEntityType,
                                             @Nullable UUID actorUserId,
                                             int pageSize,
                                             int pageOffset) {
        int clampedSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
        int clampedOffset = Math.max(pageOffset, 0);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<MutationAuditEvent> q = cb.createQuery(MutationAuditEvent.class);
        Root<MutationAuditEvent> root = q.from(MutationAuditEvent.class);

        List<Predicate> filters = new ArrayList<>();
        if (occurredFrom != null) {
            filters.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), occurredFrom));
        }
        if (occurredTo != null) {
            filters.add(cb.lessThan(root.get("occurredAt"), occurredTo));
        }
        if (action != null) {
            filters.add(cb.equal(root.get("action"), action));
        }
        if (targetEntityType != null && !targetEntityType.isBlank()) {
            filters.add(cb.equal(root.get("targetEntityType"), targetEntityType));
        }
        if (actorUserId != null) {
            filters.add(cb.equal(root.get("actorUserId"), actorUserId));
        }

        q.select(root);
        if (!filters.isEmpty()) {
            q.where(cb.and(filters.toArray(new Predicate[0])));
        }
        q.orderBy(cb.desc(root.get("occurredAt")));

        return em.createQuery(q)
                .setFirstResult(clampedOffset)
                .setMaxResults(clampedSize)
                .getResultList();
    }
}
