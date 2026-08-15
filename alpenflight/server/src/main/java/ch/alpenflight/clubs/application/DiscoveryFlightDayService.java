package ch.alpenflight.clubs.application;

import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import ch.alpenflight.clubs.application.DiscoveryFlightDayDtos.DiscoveryFlightDayResponse;
import ch.alpenflight.clubs.domain.DiscoveryFlightDay;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayAlreadyScheduledException;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayNotFoundException;
import ch.alpenflight.clubs.domain.DiscoveryFlightDayRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DiscoveryFlightDayService {

    private static final String AUDIT_ENTITY_TYPE = "DiscoveryFlightDay";

    private final DiscoveryFlightDayRepository days;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public DiscoveryFlightDayService(DiscoveryFlightDayRepository days,
                                     Clock clock,
                                     AuditTrail auditTrail) {
        this.days = days;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<DiscoveryFlightDayResponse> listDays() {
        return days.findAllActive().stream().map(DiscoveryFlightDayService::toResponse).toList();
    }

    public DiscoveryFlightDayResponse publishDay(LocalDate eventDate) {
        if (eventDate == null) {
            throw new IllegalArgumentException("eventDate is required");
        }
        days.findActiveByEventDate(eventDate).ifPresent(existing -> {
            throw new DiscoveryFlightDayAlreadyScheduledException(eventDate);
        });
        DiscoveryFlightDayResponse created = toResponse(
                days.save(DiscoveryFlightDay.schedule(eventDate, LocalDate.now(clock))));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id(), created));
        return created;
    }

    public void withdrawDay(UUID id, @Nullable UUID userId) {
        DiscoveryFlightDay day = days.findActiveById(id)
                .orElseThrow(() -> new DiscoveryFlightDayNotFoundException(id));
        DiscoveryFlightDayResponse before = toResponse(day);
        day.softDelete(userId, clock);
        days.save(day);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id, before));
    }

    private static DiscoveryFlightDayResponse toResponse(DiscoveryFlightDay day) {
        return new DiscoveryFlightDayResponse(
                Objects.requireNonNull(day.getId(), "DiscoveryFlightDay id"), day.getEventDate());
    }
}
