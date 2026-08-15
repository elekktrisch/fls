package ch.alpenflight.accounting.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;

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

    public void run(RuleBasedDeliveryDetails accumulator,
                    MatchableFlight flight,
                    RuleFilters filters,
                    int flightDurationZeroBasedSeconds,
                    int engineRunningTimeSeconds,
                    @Nullable TowInput tow) {
        run(accumulator, flight, filters, flightDurationZeroBasedSeconds,
                engineRunningTimeSeconds, tow, List.of());
    }

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

        appendTowLinesBeforeThisFlightsFuelAndTaxLines(accumulator, filters, tow, credits);

        additionalFuelFee.run(accumulator, flight, filters.additionalFuelFee());
        startTax.run(accumulator, flight, filters.startTax());
        landingTax.run(accumulator, flight, filters.landingTax());
        landingTax.runOnStartLocation(accumulator, flight, filters.landingTax());
        vsfFee.run(accumulator, flight, filters.vsfFee());
        vsfFee.runOnStartLocation(accumulator, flight, filters.vsfFee());
    }

    private void appendTowLinesBeforeThisFlightsFuelAndTaxLines(RuleBasedDeliveryDetails accumulator,
                                                                RuleFilters filters,
                                                                @Nullable TowInput tow,
                                                                List<PersonFlightTimeCredit> credits) {
        if (tow != null) {
            run(accumulator, tow.flight(), filters, tow.flightSeconds(), tow.engineSeconds(), null, credits);
        }
    }

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

    public record TowInput(MatchableFlight flight, int flightSeconds, int engineSeconds) {}
}
