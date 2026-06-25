package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
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
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

/**
 * Delivery aggregate root — the immutable invoice draft the rules engine
 * produces for a flight: the billing line items + the frozen recipient snapshot.
 * Maps the EXISTING V4 table {@code t_delivery} (substrate built ahead in V4).
 *
 * <p>{@link #createFromEligibleFlight} owns the create-from-engine-output business
 * rule (ADR 0022 directive 2): it maps a computed {@link RuleBasedDeliveryDetails}
 * into the persisted recipient snapshot + line items + batch id, rejecting an
 * un-billable engine output ({@link DeliveryPreparationException}). The
 * {@code delivery_number} is a free-text string assigned at booking (externally
 * supplied, no counter); booking + delete are separate write methods.
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

    @Column(name = "delivered_on")
    private @Nullable Instant deliveredOn;

    @Column(name = "batch_id", nullable = false)
    private long batchId;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "delivery_id", nullable = false)
    @OrderBy("position asc")
    private List<DeliveryItem> items = new ArrayList<>();

    protected Delivery() {
        // JPA.
    }

    /**
     * Builds the persisted {@code Delivery} for one eligible flight from the rules
     * engine's computed {@link RuleBasedDeliveryDetails}: the frozen recipient
     * snapshot, the line items (each {@code articleNumber} resolved to an
     * {@code article_id} via {@code articleIdResolver}), the two free-text info
     * fields, and the app-generated {@code batchId} (legacy {@code max+1}). The
     * delivery starts {@link DeliveryProcessState#PREPARED}.
     *
     * <p>A flight that the engine produced NO recipient or NO items for is a
     * preparation error, not a delivery ({@code DeliveryService.cs} —
     * {@code DeliveryPreparationError}); the batch swallows it and leaves the
     * flight in that error state. An item whose {@code articleNumber} resolves to
     * no article is likewise unbillable.
     *
     * @throws DeliveryPreparationException when the engine yields no recipient, no
     *     items, or an item whose article cannot be resolved
     */
    public static Delivery createFromEligibleFlight(UUID operatingClubId,
                                                    UUID flightId,
                                                    RuleBasedDeliveryDetails computed,
                                                    long batchId,
                                                    Function<String, @Nullable UUID> articleIdResolver) {
        Recipient recipient = computed.recipient();
        if (recipient == null) {
            throw new DeliveryPreparationException("flight " + flightId + " resolved no billing recipient");
        }
        List<DeliveryItemDetails> lines = computed.deliveryItems();
        if (lines.isEmpty()) {
            throw new DeliveryPreparationException("flight " + flightId + " produced no delivery items");
        }
        Delivery delivery = new Delivery();
        delivery.operatingClubId = operatingClubId;
        delivery.flightId = flightId;
        delivery.batchId = batchId;
        delivery.processState = DeliveryProcessState.PREPARED;
        delivery.recipientPersonId = recipient.personId();
        delivery.recipient = snapshotOf(recipient);
        delivery.deliveryInformation = computed.getDeliveryInformation();
        delivery.additionalInformation = computed.getAdditionalInformation();
        for (DeliveryItemDetails line : lines) {
            UUID articleId = articleIdResolver.apply(line.articleNumber());
            if (articleId == null) {
                throw new DeliveryPreparationException(
                        "flight " + flightId + " item article unresolved: " + line.articleNumber());
            }
            delivery.items.add(DeliveryItem.fromEngineLine(operatingClubId, articleId, line));
        }
        return delivery;
    }

    /**
     * Books this Prepared delivery ({@code DeliveryService.cs:338}): stamps the
     * free-text, request-supplied {@code deliveryNumber} (no counter / allocation),
     * the delivered timestamp, and flips the state to
     * {@link DeliveryProcessState#BOOKED} (legacy {@code IsFurtherProcessed=true}).
     * Flipping the linked flight + tow to {@code DeliveryBooked} is the caller's
     * cross-aggregate side effect.
     *
     * <p>Booked is terminal — re-booking an already-booked delivery is a
     * {@link DeliveryBookedTerminalException} (a clean 409, no mutation), mirroring
     * the delete guard ({@link #delete}).
     *
     * @throws DeliveryBookedTerminalException when this delivery is already booked
     */
    public void book(@Nullable Integer deliveryNumber, Instant deliveredAt) {
        if (deliveredAt == null) {
            throw new IllegalArgumentException("deliveredAt must not be null");
        }
        requireNotBooked();
        this.deliveryNumber = deliveryNumber;
        this.deliveredOn = deliveredAt;
        this.processState = DeliveryProcessState.BOOKED;
    }

    /**
     * Removes this delivery and (by parent invisibility) its line items
     * ({@code DeliveryService.DeleteDelivery:1226}). Soft-delete (stamp
     * {@code deleted_on}) — the read path filters {@code deleted_on is null} and
     * fetches items only through the parent, so both disappear from the list while
     * the row survives: the {@code person_flight_time_credit_transaction
     * .balanced_delivery_id} back-reference (no-cascade) and the audit trail keep a
     * resolvable FK. Resetting the linked flights + reversing the consumed credit are
     * the caller's cross-aggregate side effects. Idempotent.
     *
     * <p>A booked delivery is immutable — deleting one is a
     * {@link DeliveryBookedTerminalException} (409, no mutation), so a booked
     * billing record can never be silently dropped.
     *
     * @throws DeliveryBookedTerminalException when this delivery is already booked
     */
    public void delete(@Nullable UUID deletedByUserId, Clock clock) {
        requireNotBooked();
        softDelete(deletedByUserId, clock);
    }

    /** True once {@link #book} has stamped the delivery ({@link DeliveryProcessState#BOOKED}). */
    public boolean isBooked() {
        return processState == DeliveryProcessState.BOOKED;
    }

    private void requireNotBooked() {
        if (isBooked()) {
            throw new DeliveryBookedTerminalException(id);
        }
    }

    private static DeliveryRecipient snapshotOf(Recipient recipient) {
        return new DeliveryRecipient(
                recipient.recipientName(),
                recipient.firstname(),
                recipient.lastname(),
                null, null, null, null, null,
                recipient.personClubMemberNumber());
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

    public @Nullable Instant getDeliveredOn() {
        return deliveredOn;
    }

    public long getBatchId() {
        return batchId;
    }

    public List<DeliveryItem> getItems() {
        return List.copyOf(items);
    }
}
