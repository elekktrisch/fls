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

        for (AccountingRuleFilter filter : filters.findActiveForEngineOrderedBySortIndicatorThenId()) {
            Integer legacyType = filterTypeLegacyById.get(filter.getFilterTypeId());
            if (legacyType == null) {
                continue;
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

    record LoadedFilters(
            List<RuleFilterInput> doNotInvoice,
            List<RuleFilterInput> recipient,
            RuleFilters lineFilters) {}
}
