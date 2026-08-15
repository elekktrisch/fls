package ch.alpenflight.accounting.application;

import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterDetail;
import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterListItem;
import ch.alpenflight.accounting.application.AccountingRuleFilterDtos.AccountingRuleFilterWriteRequest;
import ch.alpenflight.accounting.domain.AccountingRuleFilter;
import ch.alpenflight.accounting.domain.AccountingRuleFilterRepository;
import ch.alpenflight.accounting.domain.FilterConfig;
import ch.alpenflight.audit.domain.AuditAction;
import ch.alpenflight.audit.domain.AuditTrail;
import ch.alpenflight.audit.domain.AuditedTarget;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountingRuleFiltersService {

    private static final String AUDIT_ENTITY_TYPE = "AccountingRuleFilter";

    private static final int TYPE_DO_NOT_INVOICE = 5;
    private static final int TYPE_RECIPIENT = 10;

    private static final int UNLIMITED_FLIGHT_TIME_SECONDS = Integer.MAX_VALUE;

    private final AccountingRuleFilterRepository filters;
    private final Clock clock;
    private final AuditTrail auditTrail;

    public AccountingRuleFiltersService(AccountingRuleFilterRepository filters,
                                        Clock clock,
                                        AuditTrail auditTrail) {
        this.filters = filters;
        this.clock = clock;
        this.auditTrail = auditTrail;
    }

    @Transactional(readOnly = true)
    public List<AccountingRuleFilterListItem> listFilters() {
        return filters.findAllActiveOrderedBySort().stream()
                .map(AccountingRuleFiltersService::toListItem)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountingRuleFilterDetail getDetail(UUID id) {
        return toDetail(loadOrThrow(id));
    }

    public AccountingRuleFilterDetail create(AccountingRuleFilterWriteRequest req) {
        AccountingRuleFilter arf = AccountingRuleFilter.create(
                req.filterTypeId(), req.ruleFilterName(), null, null,
                true, false, false, null, null, FilterConfig.empty());
        applyTo(arf, req);
        arf.assignSortIndicator(filters.nextSortIndicator());
        AccountingRuleFilterDetail created = toDetail(persist(arf));
        auditTrail.record(AuditAction.CREATE,
                AuditedTarget.created(AUDIT_ENTITY_TYPE, created.id(), created));
        return created;
    }

    public AccountingRuleFilterDetail update(UUID id, AccountingRuleFilterWriteRequest req) {
        AccountingRuleFilter arf = loadOrThrow(id);
        AccountingRuleFilterDetail before = toDetail(arf);
        applyTo(arf, req);
        AccountingRuleFilterDetail after = toDetail(persist(arf));
        auditTrail.record(AuditAction.UPDATE,
                AuditedTarget.updated(AUDIT_ENTITY_TYPE, id, before, after));
        return after;
    }

    private static void applyTo(AccountingRuleFilter arf, AccountingRuleFilterWriteRequest req) {
        FilterConfig config = normalizeForSave(
                req.filterTypeLegacyId(), req.deliveryLineText(), req.recipientName(),
                req.filterConfig());
        arf.update(
                req.filterTypeId(),
                req.ruleFilterName(),
                req.accountingUnitTypeId(),
                req.description(),
                orDefault(req.active(), true),
                orDefault(req.stopRuleEngineWhenApplied(), false),
                orDefault(req.chargedToClubInternal(), false),
                targetArticle(req.filterTypeLegacyId(), req.articleNumber()),
                targetRecipient(req.filterTypeLegacyId(), req.recipientMemberNumber()),
                config);
    }

    public void delete(UUID id, @Nullable UUID userId) {
        AccountingRuleFilter arf = loadOrThrow(id);
        AccountingRuleFilterDetail before = toDetail(arf);
        arf.softDelete(userId, clock);
        filters.save(arf);
        auditTrail.record(AuditAction.DELETE,
                AuditedTarget.deleted(AUDIT_ENTITY_TYPE, id, before));
    }


    private AccountingRuleFilter loadOrThrow(UUID id) {
        return filters.findActiveById(id)
                .orElseThrow(() -> new AccountingRuleFilterNotFoundException(id));
    }

    private AccountingRuleFilter persist(AccountingRuleFilter arf) {
        AccountingRuleFilter saved = filters.save(arf);
        filters.flush();
        return saved;
    }


    private static @Nullable String targetArticle(int filterTypeLegacyId, @Nullable String articleNumber) {
        return isArticleTarget(filterTypeLegacyId) ? blankToNull(articleNumber) : null;
    }

    private static @Nullable String targetRecipient(int filterTypeLegacyId, @Nullable String memberNumber) {
        return filterTypeLegacyId == TYPE_RECIPIENT ? blankToNull(memberNumber) : null;
    }

    private static boolean isArticleTarget(int filterTypeLegacyId) {
        return filterTypeLegacyId != TYPE_DO_NOT_INVOICE && filterTypeLegacyId != TYPE_RECIPIENT;
    }

    private static FilterConfig normalizeForSave(int filterTypeLegacyId,
                                                 @Nullable String deliveryLineText,
                                                 @Nullable String recipientName,
                                                 FilterConfig config) {
        String delivery;
        String recipient;
        if (filterTypeLegacyId == TYPE_RECIPIENT) {
            delivery = null;
            recipient = blankToNull(recipientName);
        } else if (isArticleTarget(filterTypeLegacyId)) {
            delivery = blankToNull(deliveryLineText);
            recipient = null;
        } else {
            delivery = null;
            recipient = null;
        }

        Integer min = config.minFlightTimeInSecondsMatchingValue();
        Integer max = config.maxFlightTimeInSecondsMatchingValue();
        boolean unlimited = (min == null || min <= 0)
                && (max == null || max == UNLIMITED_FLIGHT_TIME_SECONDS);
        String threshold = config.includeThresholdText() ? config.thresholdText() : null;

        return new FilterConfig(
                config.isRuleForGliderFlights(),
                config.isRuleForTowingFlights(),
                config.isRuleForMotorFlights(),
                config.noLandingTaxForGlider(),
                config.noLandingTaxForTowingAircraft(),
                config.noLandingTaxForAircraft(),
                config.includeFlightTypeName(),
                config.extendMatchingFlightTypeCodesToGliderAndTowFlight(),
                config.includeThresholdText(),
                threshold,
                unlimited ? null : min,
                unlimited ? null : max,
                config.minEngineTimeInSecondsMatchingValue(),
                config.maxEngineTimeInSecondsMatchingValue(),
                config.aircraftImmatriculations(),
                config.startTypes(),
                config.flightTypeCodes(),
                config.startLocations(),
                config.ldgLocations(),
                config.clubMemberNumbers(),
                config.flightCrewTypes(),
                config.aircraftHomebases(),
                config.memberStates(),
                config.personCategories(),
                delivery,
                recipient);
    }


    private static AccountingRuleFilterDetail toDetail(AccountingRuleFilter arf) {
        return new AccountingRuleFilterDetail(
                requireId(arf),
                requireFilterType(arf),
                arf.getAccountingUnitTypeId(),
                arf.getRuleFilterName(),
                arf.getDescription(),
                arf.isActive(),
                arf.getSortIndicator(),
                arf.isStopRuleEngineWhenApplied(),
                arf.isChargedToClubInternal(),
                arf.getArticleTarget(),
                arf.getRecipientTarget(),
                arf.getFilterConfig());
    }

    private static AccountingRuleFilterListItem toListItem(AccountingRuleFilter arf) {
        return new AccountingRuleFilterListItem(
                requireId(arf),
                arf.getRuleFilterName(),
                requireFilterType(arf),
                arf.isActive(),
                arf.getSortIndicator(),
                computeTarget(arf));
    }

    private static String computeTarget(AccountingRuleFilter arf) {
        FilterConfig config = arf.getFilterConfig();
        String recipient = arf.getRecipientTarget();
        if (recipient != null) {
            return labelled(config.recipientName(), recipient);
        }
        String article = arf.getArticleTarget();
        if (article != null) {
            return labelled(article, config.deliveryLineText());
        }
        return "";
    }

    private static String labelled(@Nullable String prefix, @Nullable String suffix) {
        String head = prefix == null ? "" : prefix.strip();
        String tail = suffix == null ? "" : suffix.strip();
        if (tail.isEmpty()) {
            return head;
        }
        return (head.isEmpty() ? "" : head + " ") + "(" + tail + ")";
    }

    private static UUID requireId(AccountingRuleFilter arf) {
        return Objects.requireNonNull(arf.getId(),
                "AccountingRuleFilter.id must be non-null after persist");
    }

    private static UUID requireFilterType(AccountingRuleFilter arf) {
        return Objects.requireNonNull(arf.getFilterTypeId(),
                "AccountingRuleFilter.filterTypeId must be non-null");
    }

    private static boolean orDefault(@Nullable Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
