package ch.alpenflight.accounting.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Assembles the per-flight line-item stages into one pass — the pure-domain port
 * of {@code flsserver/src/FLS.Server.Service/Accounting/RuleEngines/DeliveryItemRulesEngine.cs:31-200}.
 * It runs the already-built single-pass + decrement-loop stages in the legacy
 * CODE order against one flight, threading a single {@link RuleBasedDeliveryDetails}
 * accumulator through them, and recurses once into the tow flight in the middle of
 * that order. The engine orchestrator (T-12) owns the IgnoreFlight / Recipient
 * short-circuit, filter loading + fk resolution, and the tow flight's
 * {@link MatchableFlight} build; this class is just the line-item assembly.
 *
 * <p>Ported side-by-side (NOT rewritten from understanding; customer invoices
 * depend on the current behavior, the bit-exact J-9 contract). The stages run in
 * the legacy method's source order — which is NOT the filter-type-id order:
 * NoLandingTax(20) → FlightTime(30) → EngineTime(80) → InstructorFee(40) →
 * <em>tow recursion</em> → AdditionalFuelFee(50) → StartTax(55) → LandingTax(60)
 * (+OnStartLocation) → VsfFee(70) (+OnStartLocation).
 */
public final class DeliveryItemPipeline {

    private final NoLandingTaxStage noLandingTax;
    private final FlightTimeStage flightTime;
    private final EngineTimeStage engineTime;
    private final InstructorFeeStage instructorFee;
    private final AdditionalFuelFeeStage additionalFuelFee;
    private final StartTaxStage startTax;
    private final LandingTaxStage landingTax;
    private final VsfFeeStage vsfFee;

    public DeliveryItemPipeline(AccountingRuleMatcher matcher) {
        this.noLandingTax = new NoLandingTaxStage(matcher);
        this.flightTime = new FlightTimeStage(matcher);
        this.engineTime = new EngineTimeStage(matcher);
        this.instructorFee = new InstructorFeeStage(matcher);
        this.additionalFuelFee = new AdditionalFuelFeeStage(matcher);
        this.startTax = new StartTaxStage(matcher);
        this.landingTax = new LandingTaxStage(matcher);
        this.vsfFee = new VsfFeeStage(matcher);
    }

    /**
     * Runs the line-item pipeline against {@code flight}, appending its lines to
     * {@code accumulator} in the legacy code order. The same {@code filters}
     * buckets are reused for the tow flight: the legacy engine constructs the tow
     * recursion with the SAME filter list, so a type-30 filter that matched the
     * glider is re-evaluated against the tow flight too.
     *
     * @param flightDurationZeroBasedSeconds seeds the FlightTime decrement loop
     *     (legacy {@code FlightDurationZeroBased.TotalSeconds})
     * @param engineRunningTimeSeconds seeds the EngineTime decrement loop (legacy
     *     {@code EngineEnd - EngineStart} counter delta; 0 → no engine-time lines)
     * @param tow the tow flight's inputs, or {@code null} when the flight has no
     *     tow (and ALWAYS {@code null} on the recursive call — the tow has no tow,
     *     so the recursion is one level only)
     */
    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    RuleFilters filters,
                    int flightDurationZeroBasedSeconds,
                    int engineRunningTimeSeconds,
                    @Nullable TowInput tow) {
        run(accumulator, flight, filters, flightDurationZeroBasedSeconds,
                engineRunningTimeSeconds, tow, List.of());
    }

    /**
     * The credit-aware variant: {@code credits} are the billed person's prepaid
     * {@link PersonFlightTimeCredit}s, threaded into the FlightTime stage's credit
     * branch (and reused across the tow recursion). An empty list is the pure
     * decrement path.
     */
    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    RuleFilters filters,
                    int flightDurationZeroBasedSeconds,
                    int engineRunningTimeSeconds,
                    @Nullable TowInput tow,
                    List<PersonFlightTimeCredit> credits) {
        noLandingTax.run(accumulator, flight, filters.noLandingTax());
        flightTime.run(accumulator, flight, flightDurationZeroBasedSeconds, filters.flightTime(), credits);
        engineTime.run(accumulator, flight, engineRunningTimeSeconds, filters.engineTime());
        instructorFee.run(accumulator, flight, filters.instructorFee());

        // The tow recursion is placed HERE — before this flight's Fuel/StartTax/
        // Landing/Vsf lines — so the tow's lines (continuing the same position
        // numbering on the shared accumulator) appear between the glider's
        // instructor line and its remaining lines. The legacy comment names this
        // exact placement ("before other rules were applied, because of order of
        // delivery lines"); it is the non-derivable ordering quirk this class owns.
        //
        // The credits follow the billed person across the tow flight too: legacy
        // builds the tow recursion with the SAME credit list (the credit is read
        // once for the delivery, not re-resolved per flight).
        if (tow != null) {
            run(accumulator, tow.flight(), filters, tow.flightSeconds(), tow.engineSeconds(), null, credits);
        }

        additionalFuelFee.run(accumulator, flight, filters.additionalFuelFee());
        startTax.run(accumulator, flight, filters.startTax());
        landingTax.run(accumulator, flight, filters.landingTax());
        landingTax.runOnStartLocation(accumulator, flight, filters.landingTax());
        vsfFee.run(accumulator, flight, filters.vsfFee());
        vsfFee.runOnStartLocation(accumulator, flight, filters.vsfFee());
    }

    /**
     * The per-legacy-type {@link RuleFilterInput} buckets the orchestrator (T-12)
     * loads (active filters, {@code ORDER BY sort_indicator, id}) and hands the
     * pipeline. The IgnoreFlight(5) / Recipient(10) buckets are NOT here — those
     * run in the orchestrator's short-circuit, before any line-item stage.
     */
    public record RuleFilters(
            List<RuleFilterInput> noLandingTax,
            List<RuleFilterInput> flightTime,
            List<RuleFilterInput> engineTime,
            List<RuleFilterInput> instructorFee,
            List<RuleFilterInput> additionalFuelFee,
            List<RuleFilterInput> startTax,
            List<RuleFilterInput> landingTax,
            List<RuleFilterInput> vsfFee) {

        public RuleFilters {
            noLandingTax = List.copyOf(noLandingTax);
            flightTime = List.copyOf(flightTime);
            engineTime = List.copyOf(engineTime);
            instructorFee = List.copyOf(instructorFee);
            additionalFuelFee = List.copyOf(additionalFuelFee);
            startTax = List.copyOf(startTax);
            landingTax = List.copyOf(landingTax);
            vsfFee = List.copyOf(vsfFee);
        }
    }

    /**
     * The tow flight's pipeline inputs — its resolved {@link MatchableFlight} plus
     * the two seed durations the loops need. The orchestrator (T-12) builds the
     * tow's {@code MatchableFlight}; the pipeline reuses the glider's filter
     * buckets for it (the legacy recursion passes the same filter list).
     */
    public record TowInput(MatchableFlight flight, int flightSeconds, int engineSeconds) {}
}
