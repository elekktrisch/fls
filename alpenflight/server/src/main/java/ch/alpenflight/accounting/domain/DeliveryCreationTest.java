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
    }

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

    public void update(UUID flightId,
                       String testName,
                       @Nullable String description,
                       boolean active,
                       boolean mustNotCreateDeliveryForFlight,
                       IgnoreFlags ignoreFlags) {
        apply(flightId, testName, description, active, mustNotCreateDeliveryForFlight, ignoreFlags);
    }

    public void captureExpected(DeliveryDetailsSnapshot snapshot, List<UUID> matchedFilterIds) {
        this.expectedDelivery = snapshot == null ? DeliveryDetailsSnapshot.empty() : snapshot;
        this.expectedMatchedFilterIds = matchedFilterIds == null
                ? new ArrayList<>()
                : new ArrayList<>(matchedFilterIds);
        rebuildItems(this.expectedDelivery);
    }

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
