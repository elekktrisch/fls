package ch.alpenflight.accounting.domain;

import ch.alpenflight.audit.domain.AuditRedact;
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

    @AuditRedact
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "filter_config", nullable = false, columnDefinition = "jsonb")
    private FilterConfig filterConfig = FilterConfig.empty();

    protected AccountingRuleFilter() {
    }

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
