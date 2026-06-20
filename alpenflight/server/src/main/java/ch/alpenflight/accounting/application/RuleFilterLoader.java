package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.domain.AccountingRuleFilter;
import ch.alpenflight.accounting.domain.AccountingRuleFilterRepository;
import ch.alpenflight.accounting.domain.AccountingUnitType;
import ch.alpenflight.accounting.domain.DeliveryItemPipeline.RuleFilters;
import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import ch.alpenflight.accounting.domain.RuleFilterInput;
import ch.alpenflight.persons.domain.PersonRepository;
import ch.alpenflight.referencedata.domain.AccountingRuleFilterType;
import ch.alpenflight.referencedata.domain.AccountingRuleFilterTypeRepository;
import ch.alpenflight.referencedata.domain.AccountingUnitTypeRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Loads the caller-tenant's active {@link AccountingRuleFilter} rows ({@code ORDER
 * BY sort_indicator, id} — operator decision, deterministic) and resolves each
 * into the pure-data {@link RuleFilterInput} the engine stages consume, bucketed
 * by the filter's legacy filter-type int (5/10/20/30/40/50/55/60/70/80). Per
 * filter it resolves: {@code filterTypeId} UUID → legacy int (the bucket);
 * {@code accountingUnitTypeId} UUID → {@link AccountingUnitType} (line-emitting
 * buckets); {@code articleTarget} → article number; type-10 {@code recipientTarget}
 * member-number + {@code recipientName} → a self-contained {@link Recipient} VO
 * (enriched from a matching migrated Person when one exists), reproducing the
 * legacy {@code RecipientDetails} value object; null only when nothing identifies
 * the recipient (the legacy null-RecipientTarget throw).
 *
 * <p>Returns both bucketed views the orchestrator needs: the IgnoreFlight(5) +
 * Recipient(10) buckets (run in the short-circuit, before any line stage) and the
 * {@link RuleFilters} record the {@link ch.alpenflight.accounting.domain.DeliveryItemPipeline}
 * consumes.
 */
@Component
class RuleFilterLoader {

    private static final int TYPE_DO_NOT_INVOICE = 5;
    private static final int TYPE_RECIPIENT = 10;
    private static final int TYPE_NO_LANDING_TAX = 20;
    private static final int TYPE_FLIGHT_TIME = 30;
    private static final int TYPE_INSTRUCTOR_FEE = 40;
    private static final int TYPE_ADDITIONAL_FUEL_FEE = 50;
    private static final int TYPE_START_TAX = 55;
    private static final int TYPE_LANDING_TAX = 60;
    private static final int TYPE_VSF_FEE = 70;
    private static final int TYPE_ENGINE_TIME = 80;

    private final AccountingRuleFilterRepository filters;
    private final AccountingRuleFilterTypeRepository filterTypes;
    private final AccountingUnitTypeRepository unitTypes;
    private final PersonRepository persons;

    RuleFilterLoader(AccountingRuleFilterRepository filters,
                     AccountingRuleFilterTypeRepository filterTypes,
                     AccountingUnitTypeRepository unitTypes,
                     PersonRepository persons) {
        this.filters = filters;
        this.filterTypes = filterTypes;
        this.unitTypes = unitTypes;
        this.persons = persons;
    }

    LoadedFilters load() {
        Map<UUID, Integer> filterTypeLegacyById = filterTypeLegacyById();
        Map<UUID, AccountingUnitType> unitByById = unitTypeByById();
        Map<Integer, List<RuleFilterInput>> byType = new HashMap<>();

        for (AccountingRuleFilter filter : filters.findActiveForEngineOrdered()) {
            Integer legacyType = filterTypeLegacyById.get(filter.getFilterTypeId());
            if (legacyType == null) {
                continue; // unknown filter type — never bucketed, never applied.
            }
            byType.computeIfAbsent(legacyType, k -> new ArrayList<>())
                    .add(toInput(filter, legacyType, unitByById));
        }

        RuleFilters lineFilters = new RuleFilters(
                bucket(byType, TYPE_NO_LANDING_TAX),
                bucket(byType, TYPE_FLIGHT_TIME),
                bucket(byType, TYPE_ENGINE_TIME),
                bucket(byType, TYPE_INSTRUCTOR_FEE),
                bucket(byType, TYPE_ADDITIONAL_FUEL_FEE),
                bucket(byType, TYPE_START_TAX),
                bucket(byType, TYPE_LANDING_TAX),
                bucket(byType, TYPE_VSF_FEE));

        return new LoadedFilters(
                bucket(byType, TYPE_DO_NOT_INVOICE),
                bucket(byType, TYPE_RECIPIENT),
                lineFilters);
    }

    private RuleFilterInput toInput(AccountingRuleFilter filter,
                                    int legacyType,
                                    Map<UUID, AccountingUnitType> unitByById) {
        UUID id = Objects.requireNonNull(filter.getId(), "active filter id must be non-null");
        if (legacyType == TYPE_DO_NOT_INVOICE) {
            return RuleFilterInput.of(id, null, filter.getFilterConfig());
        }
        if (legacyType == TYPE_RECIPIENT) {
            return RuleFilterInput.of(id,
                    resolveRecipient(filter.getRecipientTarget(),
                            filter.getFilterConfig().recipientName()),
                    filter.getFilterConfig());
        }
        return new RuleFilterInput(
                id,
                null,
                filter.getArticleTarget(),
                resolveUnit(filter.getAccountingUnitTypeId(), unitByById),
                filter.getFilterConfig());
    }

    // The legacy RecipientTarget is a self-contained RecipientDetails value object
    // (DeliveryRecipientRule.cs:16-23 sets PersonId + RecipientName +
    // PersonClubMemberNumber straight from the blob, no Person lookup, throwing
    // only when the WHOLE blob is null). Real recipient rules route to a
    // club-internal accounting ACCOUNT — PersonId=null + a synthetic member number
    // (e.g. "999007") no Person owns — so a Person-FK lookup cannot resolve them.
    // Mirror legacy: when a migrated Person matches the member number, enrich the
    // recipient with its identity; otherwise fall back to the embedded value
    // object (member number + recipient name). Return null only when nothing
    // identifies the recipient, reproducing the legacy null-target throw.
    private @Nullable Recipient resolveRecipient(@Nullable String memberNumber,
                                                 @Nullable String recipientName) {
        String number = memberNumber == null || memberNumber.isBlank() ? null : memberNumber;
        boolean hasName = recipientName != null && !recipientName.isBlank();
        if (number == null && !hasName) {
            return null;
        }
        if (number != null) {
            for (PersonRepository.ListRow row : persons.findActiveListRowsInCurrentTenant()) {
                if (number.equals(row.memberNumber())) {
                    String name = (row.firstname() + " " + row.lastname()).strip();
                    return new Recipient(
                            row.id(), row.memberNumber(), name, row.firstname(), row.lastname());
                }
            }
        }
        return new Recipient(null, number, recipientName, null, null);
    }

    private @Nullable AccountingUnitType resolveUnit(@Nullable UUID unitTypeId,
                                                     Map<UUID, AccountingUnitType> unitByById) {
        return unitTypeId == null ? null : unitByById.get(unitTypeId);
    }

    private Map<UUID, Integer> filterTypeLegacyById() {
        Map<UUID, Integer> byId = new HashMap<>();
        for (AccountingRuleFilterType type : filterTypes.findAllByOrderByLegacyIntIdAsc()) {
            if (type.getId() != null) {
                byId.put(type.getId(), (int) type.getLegacyIntId());
            }
        }
        return byId;
    }

    private Map<UUID, AccountingUnitType> unitTypeByById() {
        Map<UUID, AccountingUnitType> byId = new HashMap<>();
        for (var type : unitTypes.findAllByOrderByLegacyIntIdAsc()) {
            if (type.getId() != null) {
                byId.put(type.getId(), AccountingUnitType.fromCode(type.getLegacyIntId()));
            }
        }
        return byId;
    }

    private static List<RuleFilterInput> bucket(Map<Integer, List<RuleFilterInput>> byType, int legacyType) {
        return byType.getOrDefault(legacyType, List.of());
    }

    /**
     * The bucketed, fk-resolved filters for one delivery: the IgnoreFlight(5) +
     * Recipient(10) lists the orchestrator runs in its short-circuit, plus the
     * {@link RuleFilters} the line-item pipeline consumes.
     */
    record LoadedFilters(
            List<RuleFilterInput> doNotInvoice,
            List<RuleFilterInput> recipient,
            RuleFilters lineFilters) {}
}
