package ch.alpenflight.accounting.domain;

import ch.alpenflight.platform.persistence.SoftDeletableAggregate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * AccountingRuleFilter aggregate root. A club admin's billing-rule predicate
 * row: it decides which flights a billing rule matches and what article /
 * recipient the rule produces. The rules engine (J-9, S-073–077) instantiates
 * runtime {@code Rule} objects from these rows; J-8 is the CONFIG surface that
 * creates / edits them. Maps the EXISTING V4 table {@code t_accounting_rule_filter}
 * (substrate built ahead in V4, never wired until this journey).
 *
 * <p>Tenant-scoped via Hibernate's {@code @TenantId} on {@code operatingClubId}
 * (ADR 0008): every read + write is auto-filtered to the caller's managing
 * tenant before the service sees the row. Legacy cross-tenant Update/Delete was
 * a tenant-leak BUG (oracle); the new stack scopes by {@code @TenantId} so a
 * cross-tenant mutation surfaces as 404 (the row is invisible).
 *
 * <p>Per ADR 0022 directive 2, business rules live on the aggregate, not the
 * schema. The aggregate owns exactly two invariants
 * ({@link InvalidAccountingRuleFilterException}):
 *
 * <ul>
 *   <li>{@code ruleFilterName} non-blank (legacy enforced client-side only).</li>
 *   <li>{@code filterTypeId} present (the discriminator driving the form +
 *       rules engine).</li>
 * </ul>
 *
 * Per-{@code filterType} target requirements are intentionally NOT enforced —
 * legacy permits empty targets (oracle); type-specific field rules belong to
 * the rules engine (J-9), not this config aggregate.
 *
 * <p>The variable predicate set is held as the typed {@link FilterConfig}
 * value object, persisted to the {@code filter_config jsonb} column via
 * {@code @JdbcTypeCode(SqlTypes.JSON)} (the audit-module jsonb pattern, but a
 * typed record rather than a raw String). {@code articleTarget} carries the
 * bare ArticleNumber string (≤50) and {@code recipientTarget} the PersonClub
 * member-number string (≤50); their descriptive text
 * ({@code deliveryLineText} / {@code recipientName}) rides inside
 * {@link FilterConfig} so the form round-trips every field.
 *
 * <p>Identity-bearing partial UNIQUE {@code ux_arf_club_sort_partial} on
 * {@code (operating_club_id, sort_indicator) WHERE deleted_on IS NULL} (V4)
 * is the structural collision guard — the service-layer next-sort pre-check
 * can't catch a concurrent INSERT race. {@code sort_indicator} order is owned
 * by the repository/service (T-04/T-05), not this aggregate.
 *
 * <p>No {@code @DomainEvents} saved-event hook: unlike FlightType (whose
 * flight-report read-model re-projects on save), this aggregate has no
 * read-model consumer in J-8 — the rules engine (J-9) reads the rows directly.
 */
@Entity
@Table(name = "t_accounting_rule_filter")
public class AccountingRuleFilter extends SoftDeletableAggregate {

    private static final int MAX_NAME_LENGTH = 250;
    private static final int MAX_TARGET_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private @Nullable UUID id;

    @TenantId
    @Column(name = "operating_club_id", nullable = false, updatable = false)
    private @Nullable UUID operatingClubId;

    @Column(name = "filter_type_id", nullable = false)
    private @Nullable UUID filterTypeId;

    @Column(name = "accounting_unit_type_id")
    private @Nullable UUID accountingUnitTypeId;

    @Column(name = "rule_filter_name", nullable = false, length = MAX_NAME_LENGTH)
    private String ruleFilterName = "";

    @Column(name = "description", columnDefinition = "text")
    private @Nullable String description;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_indicator", nullable = false)
    private int sortIndicator;

    @Column(name = "stop_rule_engine_when_applied", nullable = false)
    private boolean stopRuleEngineWhenApplied;

    @Column(name = "is_charged_to_club_internal", nullable = false)
    private boolean chargedToClubInternal;

    @Column(name = "article_target", length = MAX_TARGET_LENGTH)
    private @Nullable String articleTarget;

    @Column(name = "recipient_target", length = MAX_TARGET_LENGTH)
    private @Nullable String recipientTarget;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_config", nullable = false, columnDefinition = "jsonb")
    private FilterConfig filterConfig = FilterConfig.empty();

    protected AccountingRuleFilter() {
        // JPA.
    }

    /**
     * Factory for a new AccountingRuleFilter. The tenant
     * ({@link #operatingClubId}) is set by Hibernate's {@code @TenantId}
     * resolver on persist — do NOT pass it here. {@code sortIndicator} is
     * assigned by the service (next free slot per club, T-05) — do NOT pass it
     * here either; it defaults to 0 until the service stamps it.
     *
     * @throws InvalidAccountingRuleFilterException when {@code ruleFilterName}
     *     is blank or {@code filterTypeId} is null
     */
    public static AccountingRuleFilter create(UUID filterTypeId,
                                              String ruleFilterName,
                                              @Nullable UUID accountingUnitTypeId,
                                              @Nullable String description,
                                              boolean active,
                                              boolean stopRuleEngineWhenApplied,
                                              boolean chargedToClubInternal,
                                              @Nullable String articleTarget,
                                              @Nullable String recipientTarget,
                                              FilterConfig filterConfig) {
        AccountingRuleFilter arf = new AccountingRuleFilter();
        arf.apply(filterTypeId, ruleFilterName, accountingUnitTypeId, description,
                active, stopRuleEngineWhenApplied, chargedToClubInternal,
                articleTarget, recipientTarget, filterConfig);
        return arf;
    }

    /**
     * Replaces every mutable field atomically and re-validates the two
     * invariants. The guards fire before any assignment, so a rejected update
     * leaves the aggregate unchanged. {@code operatingClubId} and
     * {@code sortIndicator} are NOT touched here — tenancy is immutable and
     * sort order is the service's concern.
     *
     * @throws InvalidAccountingRuleFilterException when {@code ruleFilterName}
     *     is blank or {@code filterTypeId} is null
     */
    public void update(UUID filterTypeId,
                       String ruleFilterName,
                       @Nullable UUID accountingUnitTypeId,
                       @Nullable String description,
                       boolean active,
                       boolean stopRuleEngineWhenApplied,
                       boolean chargedToClubInternal,
                       @Nullable String articleTarget,
                       @Nullable String recipientTarget,
                       FilterConfig filterConfig) {
        apply(filterTypeId, ruleFilterName, accountingUnitTypeId, description,
                active, stopRuleEngineWhenApplied, chargedToClubInternal,
                articleTarget, recipientTarget, filterConfig);
    }

    /**
     * Service-owned sort position (next free slot per club / dedupe-renumber —
     * T-05). Kept off the create/update signatures so callers can't set it by
     * accident; the partial UNIQUE {@code ux_arf_club_sort_partial} is the
     * structural backstop.
     */
    public void assignSortIndicator(int sortIndicator) {
        this.sortIndicator = sortIndicator;
    }

    private void apply(UUID newFilterTypeId,
                       String newRuleFilterName,
                       @Nullable UUID newAccountingUnitTypeId,
                       @Nullable String newDescription,
                       boolean newActive,
                       boolean newStopRuleEngineWhenApplied,
                       boolean newChargedToClubInternal,
                       @Nullable String newArticleTarget,
                       @Nullable String newRecipientTarget,
                       FilterConfig newFilterConfig) {
        // Guards first — a rejected mutation leaves the aggregate untouched.
        if (newFilterTypeId == null) {
            throw new InvalidAccountingRuleFilterException("filterTypeId is required");
        }
        if (newRuleFilterName == null || newRuleFilterName.isBlank()) {
            throw new InvalidAccountingRuleFilterException("ruleFilterName must not be blank");
        }
        String trimmedName = newRuleFilterName.strip();
        if (trimmedName.length() > MAX_NAME_LENGTH) {
            throw new InvalidAccountingRuleFilterException(
                    "ruleFilterName exceeds " + MAX_NAME_LENGTH + " characters");
        }
        this.filterTypeId = newFilterTypeId;
        this.ruleFilterName = trimmedName;
        this.accountingUnitTypeId = newAccountingUnitTypeId;
        this.description = normalizeText(newDescription);
        this.active = newActive;
        this.stopRuleEngineWhenApplied = newStopRuleEngineWhenApplied;
        this.chargedToClubInternal = newChargedToClubInternal;
        this.articleTarget = normalizeTarget(newArticleTarget, "articleTarget");
        this.recipientTarget = normalizeTarget(newRecipientTarget, "recipientTarget");
        this.filterConfig = newFilterConfig == null ? FilterConfig.empty() : newFilterConfig;
    }

    private static @Nullable String normalizeText(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static @Nullable String normalizeTarget(@Nullable String value, String field) {
        String normalized = normalizeText(value);
        if (normalized != null && normalized.length() > MAX_TARGET_LENGTH) {
            throw new InvalidAccountingRuleFilterException(
                    field + " exceeds " + MAX_TARGET_LENGTH + " characters");
        }
        return normalized;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public @Nullable UUID getOperatingClubId() {
        return operatingClubId;
    }

    public @Nullable UUID getFilterTypeId() {
        return filterTypeId;
    }

    public @Nullable UUID getAccountingUnitTypeId() {
        return accountingUnitTypeId;
    }

    public String getRuleFilterName() {
        return ruleFilterName;
    }

    public @Nullable String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public int getSortIndicator() {
        return sortIndicator;
    }

    public boolean isStopRuleEngineWhenApplied() {
        return stopRuleEngineWhenApplied;
    }

    public boolean isChargedToClubInternal() {
        return chargedToClubInternal;
    }

    public @Nullable String getArticleTarget() {
        return articleTarget;
    }

    public @Nullable String getRecipientTarget() {
        return recipientTarget;
    }

    public FilterConfig getFilterConfig() {
        return filterConfig;
    }
}
