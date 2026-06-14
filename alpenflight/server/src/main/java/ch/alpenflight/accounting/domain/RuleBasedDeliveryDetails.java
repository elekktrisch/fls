package ch.alpenflight.accounting.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The mutable accumulator the rules engine threads through its pipeline. Each
 * stage may emit {@link DeliveryItemDetails}, decrement the remaining active
 * time, set a suppression flag, or record a matched filter id.
 *
 * <p>Two distinct field groups, mirroring the legacy split (its working fields
 * carried {@code [JsonIgnore]}): the <em>emitted</em> output the harness compares
 * ({@link #deliveryItems()}, {@link #recipient()}, the two information texts) and
 * the engine's <em>internal working state</em> (active times, no-landing-tax
 * flags, do-not-invoice short-circuit, club, matched filter ids) which never
 * appears in the compared payload.
 */
public final class RuleBasedDeliveryDetails {

    private final UUID clubId;

    private final List<DeliveryItemDetails> deliveryItems = new ArrayList<>();
    private @Nullable Recipient recipient;
    private @Nullable String deliveryInformation;
    private @Nullable String additionalInformation;

    private int activeFlightTimeInSeconds;
    private int activeEngineTimeInSeconds;
    private boolean noLandingTaxForGlider;
    private boolean noLandingTaxForTowFlight;
    private boolean noLandingTaxForFlight;
    private boolean doNotInvoiceFlight;
    private final Set<UUID> matchedFilterIds = new LinkedHashSet<>();

    private RuleBasedDeliveryDetails(UUID clubId) {
        this.clubId = clubId;
    }

    public static RuleBasedDeliveryDetails forClub(UUID clubId) {
        return new RuleBasedDeliveryDetails(clubId);
    }

    /**
     * Appends an emitted line, assigning the next sequential position. When a
     * line for the same article already exists the quantity is folded into it
     * instead (legacy multi-rule-match coalescing); the returned item is the
     * resulting line in either case.
     */
    public DeliveryItemDetails addItem(DeliveryItemDetails item) {
        for (int i = 0; i < deliveryItems.size(); i++) {
            DeliveryItemDetails existing = deliveryItems.get(i);
            if (existing.articleNumber().equals(item.articleNumber())) {
                DeliveryItemDetails merged = existing.addQuantity(item.quantity());
                deliveryItems.set(i, merged);
                return merged;
            }
        }
        DeliveryItemDetails positioned = item.withPosition(deliveryItems.size() + 1);
        deliveryItems.add(positioned);
        return positioned;
    }

    /**
     * Appends an emitted line at the next sequential position WITHOUT the
     * by-article coalescing {@link #addItem} does. The legacy InstructorFeeRule
     * always builds a brand-new line (it never checks for an existing same-article
     * line the way the other single-pass fee rules do), so two matching
     * instructor-fee filters with the same article must yield two distinct lines —
     * a non-derivable quirk this bypass preserves.
     */
    public DeliveryItemDetails addLineWithoutCoalesce(DeliveryItemDetails item) {
        DeliveryItemDetails positioned = item.withPosition(deliveryItems.size() + 1);
        deliveryItems.add(positioned);
        return positioned;
    }

    public void markFilterMatched(UUID filterId) {
        matchedFilterIds.add(filterId);
    }

    public List<DeliveryItemDetails> deliveryItems() {
        return List.copyOf(deliveryItems);
    }

    public UUID clubId() {
        return clubId;
    }

    public @Nullable Recipient recipient() {
        return recipient;
    }

    public void setRecipient(@Nullable Recipient recipient) {
        this.recipient = recipient;
    }

    public @Nullable String getDeliveryInformation() {
        return deliveryInformation;
    }

    public void setDeliveryInformation(@Nullable String deliveryInformation) {
        this.deliveryInformation = deliveryInformation;
    }

    public @Nullable String getAdditionalInformation() {
        return additionalInformation;
    }

    public void setAdditionalInformation(@Nullable String additionalInformation) {
        this.additionalInformation = additionalInformation;
    }

    public int getActiveFlightTimeInSeconds() {
        return activeFlightTimeInSeconds;
    }

    public void setActiveFlightTimeInSeconds(int activeFlightTimeInSeconds) {
        this.activeFlightTimeInSeconds = activeFlightTimeInSeconds;
    }

    public int getActiveEngineTimeInSeconds() {
        return activeEngineTimeInSeconds;
    }

    public void setActiveEngineTimeInSeconds(int activeEngineTimeInSeconds) {
        this.activeEngineTimeInSeconds = activeEngineTimeInSeconds;
    }

    public boolean isNoLandingTaxForGlider() {
        return noLandingTaxForGlider;
    }

    public void setNoLandingTaxForGlider(boolean noLandingTaxForGlider) {
        this.noLandingTaxForGlider = noLandingTaxForGlider;
    }

    public boolean isNoLandingTaxForTowFlight() {
        return noLandingTaxForTowFlight;
    }

    public void setNoLandingTaxForTowFlight(boolean noLandingTaxForTowFlight) {
        this.noLandingTaxForTowFlight = noLandingTaxForTowFlight;
    }

    public boolean isNoLandingTaxForFlight() {
        return noLandingTaxForFlight;
    }

    public void setNoLandingTaxForFlight(boolean noLandingTaxForFlight) {
        this.noLandingTaxForFlight = noLandingTaxForFlight;
    }

    public boolean isDoNotInvoiceFlight() {
        return doNotInvoiceFlight;
    }

    public void setDoNotInvoiceFlight(boolean doNotInvoiceFlight) {
        this.doNotInvoiceFlight = doNotInvoiceFlight;
    }

    public Set<UUID> getMatchedFilterIds() {
        return Set.copyOf(matchedFilterIds);
    }

    /**
     * The invoice recipient a {@code Recipient} rule resolves to — the
     * member-number + name subset of the legacy {@code RecipientDetails} the
     * engine actually sets (address fields are a J-10 concern).
     */
    public record Recipient(
            @Nullable UUID personId,
            @Nullable String personClubMemberNumber,
            @Nullable String recipientName,
            @Nullable String firstname,
            @Nullable String lastname) {}
}
