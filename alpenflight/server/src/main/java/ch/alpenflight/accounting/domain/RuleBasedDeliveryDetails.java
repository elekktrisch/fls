package ch.alpenflight.accounting.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
    private final Map<UUID, Long> consumedSecondsByCreditId = new LinkedHashMap<>();

    private RuleBasedDeliveryDetails(UUID clubId) {
        this.clubId = clubId;
    }

    public static RuleBasedDeliveryDetails forClub(UUID clubId) {
        return new RuleBasedDeliveryDetails(clubId);
    }

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

    public DeliveryItemDetails addLineWithoutCoalesce(DeliveryItemDetails item) {
        DeliveryItemDetails positioned = item.withPosition(deliveryItems.size() + 1);
        deliveryItems.add(positioned);
        return positioned;
    }

    public boolean hasItemForArticle(String articleNumber) {
        return deliveryItems.stream().anyMatch(item -> item.articleNumber().equals(articleNumber));
    }

    public void markFilterMatched(UUID filterId) {
        matchedFilterIds.add(filterId);
    }

    public void recordCreditConsumption(UUID creditId, long seconds) {
        if (seconds <= 0) {
            return;
        }
        consumedSecondsByCreditId.merge(creditId, seconds, Long::sum);
    }

    public List<CreditConsumption> creditConsumptions() {
        return consumedSecondsByCreditId.entrySet().stream()
                .map(e -> new CreditConsumption(e.getKey(), e.getValue()))
                .toList();
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

    public List<UUID> matchedFilterIdsInOrder() {
        return List.copyOf(matchedFilterIds);
    }

    public record Recipient(
            @Nullable UUID personId,
            @Nullable String personClubMemberNumber,
            @Nullable String recipientName,
            @Nullable String firstname,
            @Nullable String lastname) {}

    public record CreditConsumption(UUID creditId, long consumedSeconds) {}
}
