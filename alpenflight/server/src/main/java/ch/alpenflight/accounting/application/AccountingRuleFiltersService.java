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

/**
 * Transactional service for the {@link AccountingRuleFilter} aggregate. Tenant
 * scoping (ADR 0008) is structural via Hibernate's {@code @TenantId}
 * discriminator on {@code AccountingRuleFilter.operatingClubId}; role-within-tenant
 * gates live on the controller (T-06) as {@code @PreAuthorize}.
 *
 * <p>The service <em>orchestrates</em>; it does not re-implement domain rules
 * (ADR 0022 §2 — name non-blank + filter-type required are re-validated by the
 * aggregate's {@code create}/{@code update} factories). What lives here is
 * wire-shape translation, not business logic:
 *
 * <ul>
 *   <li><b>Target-by-type assignment</b> (legacy {@code $scope.save}, oracle #15):
 *       filter-type 10 → recipient target; type ∉ {5,10} → article target;
 *       type 5 (DoNotInvoice) → both cleared. The descriptive text
 *       ({@code deliveryLineText}/{@code recipientName}) is folded into the
 *       {@link FilterConfig} so the form round-trips.</li>
 *   <li><b>Threshold / duration normalisation</b>: when the rule does not
 *       "include threshold" ({@code includeThresholdText == false}) the
 *       {@code thresholdText} is nulled; when the flight duration is "unlimited"
 *       (min == 0/absent && max == {@link #UNLIMITED_FLIGHT_TIME_SECONDS}) the
 *       min/max flight-time scalars are nulled — preserving legacy save
 *       semantics.</li>
 *   <li><b>Sort assignment</b>: a new row's {@code sortIndicator} is the
 *       repository's {@code nextSortIndicator()}; the post-save {@code flush()}
 *       surfaces the {@code ux_arf_club_sort_partial} race synchronously.</li>
 *   <li><b>Cross-tenant 404</b>: every read/mutate loads via
 *       {@code findActiveById}, which the {@code @TenantId} filter scopes to the
 *       caller's club — a cross-tenant id is invisible →
 *       {@link AccountingRuleFilterNotFoundException}.</li>
 * </ul>
 *
 * <p>Mutations emit {@link AuditAction#CREATE} / {@link AuditAction#UPDATE} /
 * {@link AuditAction#DELETE} via {@link AuditTrail} (S-072: every rule change
 * affects every subsequent invoice). The {@code filterConfig} is redacted in
 * the audit snapshot (it is PII-bearing — {@code @AuditRedact} on the entity
 * field + absent from the {@code AccountingRuleFilter} redaction allow-list).
 */
@Service
@Transactional
public class AccountingRuleFiltersService {

    private static final String AUDIT_ENTITY_TYPE = "AccountingRuleFilter";

    /** Legacy {@code AccountingRuleFilterTypeId} discriminators (oracle). */
    private static final int TYPE_DO_NOT_INVOICE = 5;
    private static final int TYPE_RECIPIENT = 10;

    /** Legacy "unlimited" upper bound for flight duration (Int32.MaxValue). */
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
        // Start from an empty aggregate, then run the same apply() path as update
        // (single source of the request→aggregate field mapping). create() only
        // re-validates the two invariants, which applyTo() does again — cheap.
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

    /**
     * Single request→aggregate mapping path for both create and update: resolves
     * the target-by-type assignment + threshold/duration normalisation into the
     * {@link FilterConfig}, then replaces every mutable field via the aggregate's
     * own {@code update(...)} (which re-validates the invariants).
     */
    private static void applyTo(AccountingRuleFilter arf, AccountingRuleFilterWriteRequest req) {
        FilterConfig config = normalizeForSave(
                req.filterTypeLegacyId(), req.deliveryLineText(), req.recipientName(),
                req.filterConfig());
        arf.update(
                req.filterTypeId(),
                req.ruleFilterName(),
                req.accountingUnitTypeId(),
                req.description(),
                req.active(),
                req.stopRuleEngineWhenApplied(),
                req.chargedToClubInternal(),
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

    // -- loading / persistence --------------------------------------------------

    private AccountingRuleFilter loadOrThrow(UUID id) {
        return filters.findActiveById(id)
                .orElseThrow(() -> new AccountingRuleFilterNotFoundException(id));
    }

    private AccountingRuleFilter persist(AccountingRuleFilter arf) {
        AccountingRuleFilter saved = filters.save(arf);
        // Flush so the partial-UNIQUE (ux_arf_club_sort_partial) race surfaces
        // synchronously here rather than at tx commit — same pattern as
        // FlightTypesService's name-collision path.
        filters.flush();
        return saved;
    }

    // -- target-by-type assignment (legacy $scope.save, oracle #15) -------------

    private static @Nullable String targetArticle(int filterTypeLegacyId, @Nullable String articleNumber) {
        return isArticleTarget(filterTypeLegacyId) ? blankToNull(articleNumber) : null;
    }

    private static @Nullable String targetRecipient(int filterTypeLegacyId, @Nullable String memberNumber) {
        return filterTypeLegacyId == TYPE_RECIPIENT ? blankToNull(memberNumber) : null;
    }

    /** type ∉ {5,10} → article target (legacy {@code targetTypeArticleVisible}). */
    private static boolean isArticleTarget(int filterTypeLegacyId) {
        return filterTypeLegacyId != TYPE_DO_NOT_INVOICE && filterTypeLegacyId != TYPE_RECIPIENT;
    }

    /**
     * Single save-time normalisation pass over the predicate bag (one
     * reconstruction of the immutable {@link FilterConfig} record), reproducing
     * legacy {@code $scope.save}:
     *
     * <ul>
     *   <li><b>Target text by type</b>: recipient (10) keeps only
     *       {@code recipientName}; article (∉{5,10}) keeps only
     *       {@code deliveryLineText}; DoNotInvoice (5) clears both — mirroring
     *       legacy clearing the unused target object.</li>
     *   <li><b>Threshold</b>: {@code includeThresholdText == false} → null
     *       {@code thresholdText}.</li>
     *   <li><b>Duration</b>: "unlimited" (min ≤ 0/absent &amp;&amp; max ==
     *       Int32.MaxValue/absent) → null both min/max flight-time scalars.</li>
     * </ul>
     */
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

    // -- aggregate → DTO mapping ------------------------------------------------

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

    /**
     * The legacy computed "Target" cell: recipient →
     * {@code "{recipientName} ({memberNumber})"}; else article →
     * {@code "{articleNumber} ({deliveryLineText})"}; else empty. Derived at read
     * time from the targets + the descriptive text in {@code filterConfig} — never
     * stored.
     */
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

    /** {@code "{prefix} ({suffix})"}, dropping the parenthetical when suffix is blank. */
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

    private static @Nullable String blankToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
