package ch.alpenflight.accounting.domain;

import ch.alpenflight.audit.domain.AuditRedact;
import ch.alpenflight.platform.persistence.SoftDeletableAggregate;
import ch.alpenflight.platform.text.FreeText;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * DeliveryCreationTest aggregate root — one regression-harness row for the
 * billing rules engine (J-9, S-073–077). It pins a {@code Flight} + an EXPECTED
 * {@code DeliveryItem} set; the harness dry-runs the engine to capture that
 * expectation, then re-runs it later and diffs the engine output against the
 * stored set (gated by the nine {@code ignore*} flags) to prove the engine still
 * reproduces it. Maps the EXISTING V4 table {@code t_delivery_creation_test}
 * (substrate built ahead in V4, never wired until this journey).
 *
 * <p>Tenant-scoped via Hibernate's {@code @TenantId} on {@code operatingClubId}
 * (ADR 0008): every read + write is auto-filtered to the caller's tenant, so a
 * cross-tenant load is invisible and surfaces as 404. Identity-bearing partial
 * UNIQUE {@code ux_dct_club_flight_partial} on
 * {@code (operating_club_id, flight_id) WHERE deleted_on IS NULL} (V4) enforces
 * one live harness per (club, flight).
 *
 * <p>Per ADR 0022 directive 2 business rules live here, not in the schema. The
 * aggregate owns two create/update invariants
 * ({@link InvalidDeliveryCreationTestException}): {@code testName} non-blank and
 * {@code flightId} present. Run-state is owned by the {@link #recordRun} and
 * {@link #captureExpected} domain methods rather than open setters.
 *
 * <p>The expected / last-created delivery payloads are the typed
 * {@link DeliveryDetailsSnapshot} jsonb VO ({@code @JdbcTypeCode(SqlTypes.JSON)} —
 * the J-8 {@code FilterConfig} precedent). The two matched-filter-id lists map to
 * the V4 {@code uuid[]} columns (retyped from the never-used legacy {@code bigint[]}
 * in V43) so they can hold the engine's UUID {@code AccountingRuleFilter} ids that
 * the harness links to {@code /accountingrules/<uuid>}. The expected payload's
 * line items are ALSO persisted relationally as {@link DeliveryCreationTestItem}
 * children (the V4 {@code _item} table) — the jsonb carries the full comparable
 * graph (recipient + info texts + items) for the diff, the child rows give the
 * relational projection the legacy harness UI listed.
 *
 * <p>Per the J-8 redaction precedent the two jsonb payloads are
 * {@code @AuditRedact} (V4 COMMENT {@code pii_blob: true} — they can carry
 * recipient names); the structural fields are allow-listed in
 * {@code application.yml audit.redaction}.
 */
@Entity
@Table(name = "t_delivery_creation_test")
public class DeliveryCreationTest extends SoftDeletableAggregate {

    private static final int MAX_NAME_LENGTH = 250;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "flight_id", nullable = false)
    private @Nullable UUID flightId;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "test_name", nullable = false, length = MAX_NAME_LENGTH)
    private String testName = "";

    @Column(name = "description", columnDefinition = "text")
    private @Nullable String description;

    @AuditRedact
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_delivery", nullable = false, columnDefinition = "jsonb")
    private DeliveryDetailsSnapshot expectedDelivery = DeliveryDetailsSnapshot.empty();

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "expected_matched_filter_ids", nullable = false, columnDefinition = "uuid[]")
    private List<UUID> expectedMatchedFilterIds = new ArrayList<>();

    @Column(name = "must_not_create_delivery_for_flight", nullable = false)
    private boolean mustNotCreateDeliveryForFlight;

    // The nine diff-suppression flags (legacy DeliveryCreationTest.IgnoreXxx) the
    // run-test field-by-field comparison consults to decide which mismatches are
    // expected and so do not fail the test. Carried as the IgnoreFlags VO at the
    // domain boundary (create/update/getter), exploded to columns for the schema.
    @Column(name = "ignore_recipient_name", nullable = false)
    private boolean ignoreRecipientName;

    @Column(name = "ignore_recipient_address", nullable = false)
    private boolean ignoreRecipientAddress;

    @Column(name = "ignore_recipient_person_id", nullable = false)
    private boolean ignoreRecipientPersonId;

    @Column(name = "ignore_recipient_club_member_number", nullable = false)
    private boolean ignoreRecipientClubMemberNumber;

    @Column(name = "ignore_delivery_information", nullable = false)
    private boolean ignoreDeliveryInformation;

    @Column(name = "ignore_additional_information", nullable = false)
    private boolean ignoreAdditionalInformation;

    @Column(name = "ignore_item_positioning", nullable = false)
    private boolean ignoreItemPositioning;

    @Column(name = "ignore_item_text", nullable = false)
    private boolean ignoreItemText;

    @Column(name = "ignore_item_additional_information", nullable = false)
    private boolean ignoreItemAdditionalInformation;

    @Column(name = "last_test_run_on")
    private @Nullable Instant lastTestRunOn;

    @Column(name = "last_test_successful")
    private @Nullable Boolean lastTestSuccessful;

    @Column(name = "last_test_result_message", columnDefinition = "text")
    private @Nullable String lastTestResultMessage;

    @AuditRedact
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_test_created_delivery", columnDefinition = "jsonb")
    private @Nullable DeliveryDetailsSnapshot lastTestCreatedDelivery;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "last_test_matched_filter_ids", columnDefinition = "uuid[]")
    private @Nullable List<UUID> lastTestMatchedFilterIds;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "delivery_creation_test_id", nullable = false)
    @OrderBy("position asc")
    private List<DeliveryCreationTestItem> items = new ArrayList<>();

    protected DeliveryCreationTest() {
        // JPA.
    }

    /**
     * Factory for a new harness. {@code operatingClubId} is the tenant stamp:
     * unlike the childless {@code AccountingRuleFilter} (which leaves it to the
     * resolver), this aggregate's internal {@link DeliveryCreationTestItem}
     * children carry a denormalized {@code operating_club_id} that must be set at
     * construction (the PlanningDay/PlanningDayAssignment precedent) — so the club
     * is an explicit param, and Hibernate's {@code @TenantId} resolver
     * re-confirms it on INSERT. The expected delivery + matched ids + relational
     * items start empty; a dry-run fills them via {@link #captureExpected}.
     *
     * @throws InvalidDeliveryCreationTestException when {@code testName} is blank
     *     or {@code flightId} is null
     */
    public static DeliveryCreationTest create(UUID operatingClubId,
                                              UUID flightId,
                                              String testName,
                                              @Nullable String description,
                                              boolean active,
                                              boolean mustNotCreateDeliveryForFlight,
                                              IgnoreFlags ignoreFlags) {
        if (operatingClubId == null) {
            throw new InvalidDeliveryCreationTestException("operatingClubId is required");
        }
        DeliveryCreationTest test = new DeliveryCreationTest();
        test.operatingClubId = operatingClubId;
        test.apply(flightId, testName, description, active, mustNotCreateDeliveryForFlight, ignoreFlags);
        return test;
    }

    /**
     * Replaces every editable header field atomically and re-validates the two
     * invariants. The guards fire before any assignment, so a rejected update
     * leaves the aggregate unchanged. {@code operatingClubId} (immutable tenancy)
     * and the captured expected payload / run-state are NOT touched here.
     *
     * @throws InvalidDeliveryCreationTestException when {@code testName} is blank
     *     or {@code flightId} is null
     */
    public void update(UUID flightId,
                       String testName,
                       @Nullable String description,
                       boolean active,
                       boolean mustNotCreateDeliveryForFlight,
                       IgnoreFlags ignoreFlags) {
        apply(flightId, testName, description, active, mustNotCreateDeliveryForFlight, ignoreFlags);
    }

    /**
     * Records a dry-run's output as this harness's EXPECTED set: the typed jsonb
     * snapshot (recipient + info + items), the matched-filter ids, and the
     * relational item children (rebuilt atomically — orphan removal drops the
     * previous set). The line-item unit prices are not part of the engine's
     * compared output, so the child rows carry the snapshot's items with a null
     * unit price.
     */
    public void captureExpected(DeliveryDetailsSnapshot snapshot, List<UUID> matchedFilterIds) {
        this.expectedDelivery = snapshot == null ? DeliveryDetailsSnapshot.empty() : snapshot;
        this.expectedMatchedFilterIds = matchedFilterIds == null
                ? new ArrayList<>()
                : new ArrayList<>(matchedFilterIds);
        rebuildItems(this.expectedDelivery);
    }

    /**
     * Records the result of running the engine against the stored flight and
     * diffing it against the expected set — the harness run-state domain method
     * (legacy {@code LastTestSuccessful} / {@code LastTestResultMessage} /
     * {@code LastTestCreatedDelivery} / {@code LastTestMatchedAccountingRuleFilterIds}).
     */
    public void recordRun(boolean successful,
                          @Nullable String resultMessage,
                          @Nullable DeliveryDetailsSnapshot createdDelivery,
                          List<UUID> matchedFilterIds,
                          Instant runOn) {
        this.lastTestSuccessful = successful;
        this.lastTestResultMessage = resultMessage;
        this.lastTestCreatedDelivery = createdDelivery;
        this.lastTestMatchedFilterIds = matchedFilterIds == null
                ? new ArrayList<>()
                : new ArrayList<>(matchedFilterIds);
        this.lastTestRunOn = runOn;
    }

    private void apply(UUID newFlightId,
                       String newTestName,
                       @Nullable String newDescription,
                       boolean newActive,
                       boolean newMustNotCreateDeliveryForFlight,
                       IgnoreFlags newIgnoreFlags) {
        // Guards first — a rejected mutation leaves the aggregate untouched.
        if (newFlightId == null) {
            throw new InvalidDeliveryCreationTestException("flightId is required");
        }
        if (newTestName == null || newTestName.isBlank()) {
            throw new InvalidDeliveryCreationTestException("testName must not be blank");
        }
        String trimmedName = newTestName.strip();
        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new InvalidDeliveryCreationTestException(
                    "testName exceeds " + MAX_NAME_LENGTH + " characters");
        }
        this.flightId = newFlightId;
        this.testName = trimmedName;
        this.description = FreeText.normalize(newDescription, Integer.MAX_VALUE);
        this.active = newActive;
        this.mustNotCreateDeliveryForFlight = newMustNotCreateDeliveryForFlight;
        IgnoreFlags flags = newIgnoreFlags == null ? IgnoreFlags.none() : newIgnoreFlags;
        this.ignoreRecipientName = flags.recipientName();
        this.ignoreRecipientAddress = flags.recipientAddress();
        this.ignoreRecipientPersonId = flags.recipientPersonId();
        this.ignoreRecipientClubMemberNumber = flags.recipientClubMemberNumber();
        this.ignoreDeliveryInformation = flags.deliveryInformation();
        this.ignoreAdditionalInformation = flags.additionalInformation();
        this.ignoreItemPositioning = flags.itemPositioning();
        this.ignoreItemText = flags.itemText();
        this.ignoreItemAdditionalInformation = flags.itemAdditionalInformation();
    }

    private void rebuildItems(DeliveryDetailsSnapshot snapshot) {
        items.clear();
        for (DeliveryItemDetails detail : snapshot.items()) {
            DeliveryCreationTestItem child = DeliveryCreationTestItem.of(detail, null);
            child.assignOperatingClub(operatingClubId);
            items.add(child);
        }
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public @Nullable UUID getFlightId() {
        return flightId;
    }

    public boolean isActive() {
        return active;
    }

    public String getTestName() {
        return testName;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public DeliveryDetailsSnapshot getExpectedDelivery() {
        return expectedDelivery;
    }

    public List<UUID> getExpectedMatchedFilterIds() {
        return List.copyOf(expectedMatchedFilterIds);
    }

    public boolean isMustNotCreateDeliveryForFlight() {
        return mustNotCreateDeliveryForFlight;
    }

    public IgnoreFlags getIgnoreFlags() {
        return new IgnoreFlags(
                ignoreRecipientName,
                ignoreRecipientAddress,
                ignoreRecipientPersonId,
                ignoreRecipientClubMemberNumber,
                ignoreDeliveryInformation,
                ignoreAdditionalInformation,
                ignoreItemPositioning,
                ignoreItemText,
                ignoreItemAdditionalInformation);
    }

    public @Nullable Instant getLastTestRunOn() {
        return lastTestRunOn;
    }

    public @Nullable Boolean getLastTestSuccessful() {
        return lastTestSuccessful;
    }

    public @Nullable String getLastTestResultMessage() {
        return lastTestResultMessage;
    }

    public @Nullable DeliveryDetailsSnapshot getLastTestCreatedDelivery() {
        return lastTestCreatedDelivery;
    }

    public List<UUID> getLastTestMatchedFilterIds() {
        return lastTestMatchedFilterIds == null ? List.of() : List.copyOf(lastTestMatchedFilterIds);
    }

    public List<DeliveryCreationTestItem> getItems() {
        return List.copyOf(items);
    }
}
