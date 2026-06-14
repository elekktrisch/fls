package ch.alpenflight.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountingRuleFilterDomainTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-06-13T10:00:00Z"), ZoneOffset.UTC);

    private static final UUID FILTER_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID UNIT_TYPE = UUID.fromString("00000000-0000-0000-0000-000000000020");

    @Test
    void create_minimalFilter_setsFieldsAndDefaults() {
        AccountingRuleFilter arf = AccountingRuleFilter.create(
                FILTER_TYPE, "Landegebühr", null, null,
                true, false, false, null, null, FilterConfig.empty());

        assertThat(arf.getFilterTypeId()).isEqualTo(FILTER_TYPE);
        assertThat(arf.getRuleFilterName()).isEqualTo("Landegebühr");
        assertThat(arf.isActive()).isTrue();
        assertThat(arf.isStopRuleEngineWhenApplied()).isFalse();
        assertThat(arf.isChargedToClubInternal()).isFalse();
        assertThat(arf.getSortIndicator()).isZero();
        assertThat(arf.getArticleTarget()).isNull();
        assertThat(arf.getRecipientTarget()).isNull();
        assertThat(arf.getFilterConfig()).isEqualTo(FilterConfig.empty());
        assertThat(arf.isDeleted()).isFalse();
    }

    @Test
    void create_blankName_rejects() {
        assertThatThrownBy(() -> AccountingRuleFilter.create(
                FILTER_TYPE, "   ", null, null,
                true, false, false, null, null, FilterConfig.empty()))
                .isInstanceOf(InvalidAccountingRuleFilterException.class)
                .hasMessageContaining("ruleFilterName");
    }

    @Test
    void create_nullFilterType_rejects() {
        assertThatThrownBy(() -> AccountingRuleFilter.create(
                null, "Tax rule", null, null,
                true, false, false, null, null, FilterConfig.empty()))
                .isInstanceOf(InvalidAccountingRuleFilterException.class)
                .hasMessageContaining("filterTypeId");
    }

    @Test
    void create_targetOverLength_rejects() {
        String tooLong = "A".repeat(51);
        assertThatThrownBy(() -> AccountingRuleFilter.create(
                FILTER_TYPE, "Has long article", null, null,
                true, false, false, tooLong, null, FilterConfig.empty()))
                .isInstanceOf(InvalidAccountingRuleFilterException.class)
                .hasMessageContaining("articleTarget");
    }

    @Test
    void create_trimsNameDescriptionAndBlankTargetsToNull() {
        AccountingRuleFilter arf = AccountingRuleFilter.create(
                FILTER_TYPE, "  Padded name  ", UNIT_TYPE, "  some text  ",
                true, true, true, "   ", "M-123", FilterConfig.empty());

        assertThat(arf.getRuleFilterName()).isEqualTo("Padded name");
        assertThat(arf.getDescription()).isEqualTo("some text");
        assertThat(arf.getAccountingUnitTypeId()).isEqualTo(UNIT_TYPE);
        // Blank article target normalises to null; recipient passes through.
        assertThat(arf.getArticleTarget()).isNull();
        assertThat(arf.getRecipientTarget()).isEqualTo("M-123");
        assertThat(arf.isStopRuleEngineWhenApplied()).isTrue();
        assertThat(arf.isChargedToClubInternal()).isTrue();
    }

    @Test
    void update_replacesEveryFieldAtomically() {
        AccountingRuleFilter arf = AccountingRuleFilter.create(
                FILTER_TYPE, "Original", null, null,
                true, false, false, "ART-1", null, FilterConfig.empty());

        UUID newType = UUID.fromString("00000000-0000-0000-0000-000000000030");
        FilterConfig cfg = FilterConfig.empty();
        arf.update(newType, "Renamed", UNIT_TYPE, "desc",
                false, true, true, "ART-2", "M-9", cfg);

        assertThat(arf.getFilterTypeId()).isEqualTo(newType);
        assertThat(arf.getRuleFilterName()).isEqualTo("Renamed");
        assertThat(arf.getAccountingUnitTypeId()).isEqualTo(UNIT_TYPE);
        assertThat(arf.getDescription()).isEqualTo("desc");
        assertThat(arf.isActive()).isFalse();
        assertThat(arf.isStopRuleEngineWhenApplied()).isTrue();
        assertThat(arf.isChargedToClubInternal()).isTrue();
        assertThat(arf.getArticleTarget()).isEqualTo("ART-2");
        assertThat(arf.getRecipientTarget()).isEqualTo("M-9");
    }

    @Test
    void update_blankName_rejectsWithoutMutating() {
        AccountingRuleFilter arf = AccountingRuleFilter.create(
                FILTER_TYPE, "Keep me", null, null,
                true, false, false, null, null, FilterConfig.empty());

        assertThatThrownBy(() -> arf.update(
                FILTER_TYPE, "  ", null, null,
                false, false, false, null, null, FilterConfig.empty()))
                .isInstanceOf(InvalidAccountingRuleFilterException.class);

        // Guard fires before assignment — nothing changed.
        assertThat(arf.getRuleFilterName()).isEqualTo("Keep me");
        assertThat(arf.isActive()).isTrue();
    }

    @Test
    void assignSortIndicator_setsServiceOwnedPosition() {
        AccountingRuleFilter arf = AccountingRuleFilter.create(
                FILTER_TYPE, "Sorted", null, null,
                true, false, false, null, null, FilterConfig.empty());
        arf.assignSortIndicator(7);
        assertThat(arf.getSortIndicator()).isEqualTo(7);
    }

    @Test
    void softDelete_idempotent() {
        AccountingRuleFilter arf = AccountingRuleFilter.create(
                FILTER_TYPE, "Doomed", null, null,
                true, false, false, null, null, FilterConfig.empty());
        UUID actor = UUID.randomUUID();
        arf.softDelete(actor, FIXED_CLOCK);
        assertThat(arf.isDeleted()).isTrue();
        arf.softDelete(actor, FIXED_CLOCK);
        assertThat(arf.isDeleted()).isTrue();
    }

    @Test
    void filterConfig_emptyHasLegacyMatchListDefaults() {
        FilterConfig cfg = FilterConfig.empty();
        // Legacy default: each match-list applies to ALL except the (empty) list.
        assertThat(cfg.aircraftImmatriculations().useAllExcept()).isTrue();
        assertThat(cfg.aircraftImmatriculations().matched()).isEmpty();
        assertThat(cfg.personCategories().useAllExcept()).isTrue();
        assertThat(cfg.thresholdText()).isNull();
        assertThat(cfg.minFlightTimeInSecondsMatchingValue()).isNull();
        assertThat(cfg.deliveryLineText()).isNull();
        assertThat(cfg.recipientName()).isNull();
        assertThat(cfg.isRuleForGliderFlights()).isFalse();
    }

    @Test
    void filterConfig_roundTripsPredicatesAndTextFields() {
        FilterConfig.MatchList immat = new FilterConfig.MatchList(false, List.of("HB-ABC", "HB-DEF"));
        FilterConfig cfg = new FilterConfig(
                true, false, false,
                false, false, false,
                true, false, true,
                "over 30min", 1800, 3600, null, null,
                immat,
                FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(),
                FilterConfig.MatchList.empty(),
                "Towing fee line", "Hans Muster");

        AccountingRuleFilter arf = AccountingRuleFilter.create(
                FILTER_TYPE, "Towing rule", null, null,
                true, false, false, "ART-TOW", null, cfg);

        FilterConfig stored = arf.getFilterConfig();
        assertThat(stored.isRuleForGliderFlights()).isTrue();
        assertThat(stored.thresholdText()).isEqualTo("over 30min");
        assertThat(stored.minFlightTimeInSecondsMatchingValue()).isEqualTo(1800);
        assertThat(stored.aircraftImmatriculations().useAllExcept()).isFalse();
        assertThat(stored.aircraftImmatriculations().matched()).containsExactly("HB-ABC", "HB-DEF");
        assertThat(stored.deliveryLineText()).isEqualTo("Towing fee line");
        assertThat(stored.recipientName()).isEqualTo("Hans Muster");
    }

    @Test
    void filterConfig_matchList_copiesMatchedListDefensively() {
        java.util.ArrayList<String> mutable = new java.util.ArrayList<>(List.of("X"));
        FilterConfig.MatchList list = new FilterConfig.MatchList(false, mutable);
        mutable.add("Y");
        // The record copied the list at construction — the external mutation
        // does not leak in.
        assertThat(list.matched()).containsExactly("X");
    }
}
