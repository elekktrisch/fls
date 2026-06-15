package ch.alpenflight.accounting.domain;

import ch.alpenflight.audit.domain.AuditRedact;
import ch.alpenflight.platform.persistence.SoftDeletableAggregate;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Delivery aggregate root — the immutable invoice draft the rules engine
 * produces for a flight: the billing line items + the frozen recipient snapshot.
 * Maps the EXISTING V4 table {@code t_delivery} (substrate built ahead in V4).
 *
 * <p><strong>Read-only this iteration.</strong> The aggregate maps the columns +
 * exposes getters; the write side — {@code create}, {@code book()} with gap-free
 * numbering, delete, the Prepared→Booked state machine — is deferred. Per ADR 0022
 * directive 2 this iteration adds no business rules.
 *
 * <p>Tenant-scoped via Hibernate's {@code @TenantId} on {@code operatingClubId}
 * (ADR 0008): every read is auto-filtered to the caller's tenant, so a
 * cross-tenant load is invisible and surfaces as 404. {@code process_state_id}
 * (V4 sparse SMALLINT 10/20/30/99) maps to {@link DeliveryProcessState} via
 * {@link DeliveryProcessState.DeliveryProcessStateConverter}; the nine frozen
 * {@code recipient_*} columns map to the {@link DeliveryRecipient}
 * {@code @Embedded} VO (OR Art. 957a).
 */
@Entity
@Table(name = "t_delivery")
public class Delivery extends SoftDeletableAggregate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Convert(converter = DeliveryProcessState.DeliveryProcessStateConverter.class)
    @Column(name = "process_state_id", nullable = false)
    private DeliveryProcessState processState = DeliveryProcessState.PREPARED;

    @Column(name = "flight_id")
    private @Nullable UUID flightId;

    @Column(name = "recipient_person_id")
    private @Nullable UUID recipientPersonId;

    // The nine frozen recipient_* columns are member PII (name + address per
    // OR Art. 957a). @AuditRedact lands the whole embedded VO "[redacted]" in the
    // audit snapshot (the AccountingRuleFilter.filterConfig PII-blob precedent).
    @AuditRedact
    @Embedded
    private DeliveryRecipient recipient = DeliveryRecipient.empty();

    @Column(name = "delivery_information", length = 250)
    private @Nullable String deliveryInformation;

    @Column(name = "additional_information", length = 250)
    private @Nullable String additionalInformation;

    @Column(name = "delivery_number")
    private @Nullable Integer deliveryNumber;

    @Column(name = "batch_id", nullable = false)
    private long batchId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "delivery_id", nullable = false)
    @OrderBy("position asc")
    private List<DeliveryItem> items = new ArrayList<>();

    protected Delivery() {
        // JPA.
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public DeliveryProcessState getProcessState() {
        return processState;
    }

    public @Nullable UUID getFlightId() {
        return flightId;
    }

    public @Nullable UUID getRecipientPersonId() {
        return recipientPersonId;
    }

    public DeliveryRecipient getRecipient() {
        return recipient;
    }

    public @Nullable String getDeliveryInformation() {
        return deliveryInformation;
    }

    public @Nullable String getAdditionalInformation() {
        return additionalInformation;
    }

    public @Nullable Integer getDeliveryNumber() {
        return deliveryNumber;
    }

    public long getBatchId() {
        return batchId;
    }

    public List<DeliveryItem> getItems() {
        return List.copyOf(items);
    }
}
