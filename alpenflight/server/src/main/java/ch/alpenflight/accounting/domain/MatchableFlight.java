package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import ch.alpenflight.flights.domain.FlightAircraftType;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The rules-engine's resolved view of a {@link ch.alpenflight.flights.domain.Flight}
 * — every facet the matcher compares already turned from its stored id into the
 * string the legacy filter holds.
 *
 * <p>The legacy {@code BaseAccountingRule} matched a flight against a filter by
 * its <em>resolved</em> values: aircraft by immatriculation, flight-type by
 * {@code FlightCode}, locations by ICAO, crew by PersonClub member-number — none
 * of which live on the alpenflight {@code Flight} aggregate (it stores only the
 * fk ids). Resolving those references (immat / code / ICAO / member#) is the
 * engine orchestrator's job (T-12), which loads the reference rows and builds
 * this value object; the matcher then operates on pure data, with no JPA / no
 * reference-data dependency. Constructing one directly is how a unit test drives
 * the matcher without a DB.
 *
 * <p>The four string facets the legacy oracle case-folds (immatriculation,
 * flight-type code, the two location ICAOs) are NOT folded here — they are
 * compared case-insensitively inside the matcher so this value object stays a
 * faithful snapshot of the resolved strings.
 */
public final class MatchableFlight {

    private final FlightAircraftType flightAircraftType;
    private final @Nullable String immatriculation;
    private final @Nullable String flightTypeCode;
    private final @Nullable String flightTypeName;
    private final @Nullable String startTypeId;
    private final @Nullable String startLocationIcao;
    private final @Nullable String ldgLocationIcao;
    private final @Nullable String aircraftHomebase;
    private final boolean aircraftPresent;
    private final @Nullable String towFlightTypeCode;
    private final List<String> towedFlightTypeCodes;
    private final List<MatchableCrew> crew;
    private final int flightCostBalanceTypeId;
    private final @Nullable Recipient flightCostInvoiceRecipient;
    private final @Nullable Recipient pilot;
    private final int flightDurationSeconds;
    private final @Nullable Integer nrOfLdgs;
    private final @Nullable Integer nrOfLdgsOnStartLocation;
    private final boolean noStartTimeInformation;
    private final boolean noLdgTimeInformation;

    private MatchableFlight(Builder builder) {
        this.flightAircraftType = builder.flightAircraftType;
        this.immatriculation = builder.immatriculation;
        this.flightTypeCode = builder.flightTypeCode;
        this.flightTypeName = builder.flightTypeName;
        this.startTypeId = builder.startTypeId;
        this.startLocationIcao = builder.startLocationIcao;
        this.ldgLocationIcao = builder.ldgLocationIcao;
        this.aircraftHomebase = builder.aircraftHomebase;
        this.aircraftPresent = builder.aircraftPresent;
        this.towFlightTypeCode = builder.towFlightTypeCode;
        this.towedFlightTypeCodes = List.copyOf(builder.towedFlightTypeCodes);
        this.crew = List.copyOf(builder.crew);
        this.flightCostBalanceTypeId = builder.flightCostBalanceTypeId;
        this.flightCostInvoiceRecipient = builder.flightCostInvoiceRecipient;
        this.pilot = builder.pilot;
        this.flightDurationSeconds = builder.flightDurationSeconds;
        this.nrOfLdgs = builder.nrOfLdgs;
        this.nrOfLdgsOnStartLocation = builder.nrOfLdgsOnStartLocation;
        this.noStartTimeInformation = builder.noStartTimeInformation;
        this.noLdgTimeInformation = builder.noLdgTimeInformation;
    }

    public static Builder builder(FlightAircraftType flightAircraftType) {
        return new Builder(flightAircraftType);
    }

    /**
     * The sparse aircraft-type bit the legacy bitmask test runs against:
     * GLIDER=1, TOW=2, MOTOR=4 (see {@link FlightAircraftType#legacyId()}).
     */
    public int flightAircraftTypeBit() {
        return flightAircraftType.legacyId();
    }

    public @Nullable String immatriculation() {
        return immatriculation;
    }

    public @Nullable String flightTypeCode() {
        return flightTypeCode;
    }

    /**
     * The human flight-type name (legacy {@code FlightType.FlightTypeName}), used
     * only in an emitted line's {@code itemText} when a filter sets
     * {@code includeFlightTypeName} — distinct from {@link #flightTypeCode()},
     * which the matcher compares.
     */
    public @Nullable String flightTypeName() {
        return flightTypeName;
    }

    public @Nullable String startTypeId() {
        return startTypeId;
    }

    public @Nullable String startLocationIcao() {
        return startLocationIcao;
    }

    public @Nullable String ldgLocationIcao() {
        return ldgLocationIcao;
    }

    public @Nullable String aircraftHomebase() {
        return aircraftHomebase;
    }

    /**
     * Whether the flight carries an aircraft reference at all. Legacy treats a
     * flight with no Aircraft as: no homebase condition added (warn only), so
     * the homebase facet then never excludes / never includes it.
     */
    public boolean aircraftPresent() {
        return aircraftPresent;
    }

    public @Nullable String towFlightTypeCode() {
        return towFlightTypeCode;
    }

    public List<String> towedFlightTypeCodes() {
        return towedFlightTypeCodes;
    }

    public List<MatchableCrew> crew() {
        return crew;
    }

    /**
     * The legacy {@code FlightCostBalanceTypeId} as its legacy int (resolved
     * from the flight's cost-balance-type reference by the engine orchestrator;
     * {@code 0} when the flight has none, mirroring {@code GetValueOrDefault}).
     * The two recipient fallback rules switch on it: CostsPaidByPerson=5 →
     * invoice-recipient crew member; PilotPaysAllCosts=1 / NoInstructorFee=4 →
     * pilot.
     */
    public int flightCostBalanceTypeId() {
        return flightCostBalanceTypeId;
    }

    /**
     * The pre-resolved recipient for the flight's {@code FlightCostInvoiceRecipient}
     * crew member (legacy FlightCrewType=10), or {@code null} when the flight
     * carries no such crew row. Resolution (person → name + member-number) is
     * the orchestrator's (T-12) job so the fallback rules stay JPA-free.
     */
    public @Nullable Recipient flightCostInvoiceRecipient() {
        return flightCostInvoiceRecipient;
    }

    /**
     * The pre-resolved recipient for the flight's pilot, or {@code null} when
     * unresolved. Same orchestrator-resolves-it contract as
     * {@link #flightCostInvoiceRecipient()}.
     */
    public @Nullable Recipient pilot() {
        return pilot;
    }

    /**
     * The flight's zero-based duration in seconds (legacy
     * {@code FlightDurationZeroBased.TotalSeconds}) — the value the landing/tax
     * stages run their min-exclusive/max-inclusive {@code Between} window against.
     */
    public int flightDurationSeconds() {
        return flightDurationSeconds;
    }

    /**
     * The flight's landing count (legacy {@code NrOfLdgs}), the LandingTax line
     * quantity; {@code null} reproduces the legacy {@code GetValueOrDefault(1)}.
     */
    public @Nullable Integer nrOfLdgs() {
        return nrOfLdgs;
    }

    /**
     * Landings made at the flight's start location (legacy
     * {@code NrOfLdgsOnStartLocation}) — the LandingTaxOnStartLocation line
     * quantity; that second pass is forced off when this is {@code <= 0}.
     */
    public @Nullable Integer nrOfLdgsOnStartLocation() {
        return nrOfLdgsOnStartLocation;
    }

    /**
     * Whether the flight lacks start-time information (legacy
     * {@code NoStartTimeInformation}); with {@link #noLdgTimeInformation()} it
     * makes LandingTax skip its duration window ("assume in the air").
     */
    public boolean noStartTimeInformation() {
        return noStartTimeInformation;
    }

    /** Whether the flight lacks landing-time information (legacy {@code NoLdgTimeInformation}). */
    public boolean noLdgTimeInformation() {
        return noLdgTimeInformation;
    }

    /**
     * Whether this is a glider flight — the legacy
     * {@code FlightAircraftType == GliderFlight} test the no-landing-tax
     * suppression reads, derived from the aircraft-type bit (GLIDER=1).
     */
    public boolean isGlider() {
        return flightAircraftType == FlightAircraftType.GLIDER;
    }

    /** Whether this is a tow flight (legacy {@code FlightAircraftType == TowFlight}, TOW=2). */
    public boolean isTow() {
        return flightAircraftType == FlightAircraftType.TOW;
    }

    /**
     * A copy with {@code ldgLocationIcao} swapped to the given value — how the
     * LandingTaxOnStartLocation second pass re-runs the existing matcher with the
     * flight's START-location ICAO standing in for the landing location (the
     * legacy override checks the start location against the SAME ldg-location
     * set; the matcher's null-ldg-location warn-only branch mirrors the override's
     * null-start-location warn).
     */
    public MatchableFlight withLdgLocationIcao(@Nullable String value) {
        return new Builder(flightAircraftType)
                .immatriculation(immatriculation)
                .flightTypeCode(flightTypeCode)
                .flightTypeName(flightTypeName)
                .startTypeId(startTypeId)
                .startLocationIcao(startLocationIcao)
                .ldgLocationIcao(value)
                .aircraftHomebase(aircraftHomebase)
                .aircraftPresent(aircraftPresent)
                .towFlightTypeCode(towFlightTypeCode)
                .towedFlightTypeCodes(towedFlightTypeCodes)
                .crew(crew)
                .flightCostBalanceTypeId(flightCostBalanceTypeId)
                .flightCostInvoiceRecipient(flightCostInvoiceRecipient)
                .pilot(pilot)
                .flightDurationSeconds(flightDurationSeconds)
                .nrOfLdgs(nrOfLdgs)
                .nrOfLdgsOnStartLocation(nrOfLdgsOnStartLocation)
                .noStartTimeInformation(noStartTimeInformation)
                .noLdgTimeInformation(noLdgTimeInformation)
                .build();
    }

    /**
     * One resolved crew row. {@code memberNumber} / {@code memberStateId} are
     * the values resolved from the crew person's PersonClub for the delivery's
     * club; a {@code null} signals the person has NO PersonClub for that club —
     * the legacy {@code PersonClubs.First(...)} would throw, which the matcher
     * reproduces ({@link MissingPersonClubException}) when a crew-scoped
     * condition needs the value.
     */
    public record MatchableCrew(
            String flightCrewTypeId,
            @Nullable String memberNumber,
            @Nullable String memberStateId,
            List<String> personCategoryIds) {

        public MatchableCrew {
            personCategoryIds = personCategoryIds == null ? List.of() : List.copyOf(personCategoryIds);
        }

        public static MatchableCrew of(String flightCrewTypeId,
                                       @Nullable String memberNumber,
                                       @Nullable String memberStateId,
                                       List<String> personCategoryIds) {
            return new MatchableCrew(flightCrewTypeId, memberNumber, memberStateId, personCategoryIds);
        }
    }

    public static final class Builder {

        private final FlightAircraftType flightAircraftType;
        private @Nullable String immatriculation;
        private @Nullable String flightTypeCode;
        private @Nullable String flightTypeName;
        private @Nullable String startTypeId;
        private @Nullable String startLocationIcao;
        private @Nullable String ldgLocationIcao;
        private @Nullable String aircraftHomebase;
        private boolean aircraftPresent = true;
        private @Nullable String towFlightTypeCode;
        private List<String> towedFlightTypeCodes = new ArrayList<>();
        private List<MatchableCrew> crew = new ArrayList<>();
        private int flightCostBalanceTypeId;
        private @Nullable Recipient flightCostInvoiceRecipient;
        private @Nullable Recipient pilot;
        private int flightDurationSeconds;
        private @Nullable Integer nrOfLdgs;
        private @Nullable Integer nrOfLdgsOnStartLocation;
        private boolean noStartTimeInformation;
        private boolean noLdgTimeInformation;

        private Builder(FlightAircraftType flightAircraftType) {
            if (flightAircraftType == null) {
                throw new IllegalArgumentException("flightAircraftType must not be null");
            }
            this.flightAircraftType = flightAircraftType;
        }

        public Builder immatriculation(@Nullable String value) {
            this.immatriculation = value;
            return this;
        }

        public Builder flightTypeCode(@Nullable String value) {
            this.flightTypeCode = value;
            return this;
        }

        public Builder flightTypeName(@Nullable String value) {
            this.flightTypeName = value;
            return this;
        }

        public Builder startTypeId(@Nullable String value) {
            this.startTypeId = value;
            return this;
        }

        public Builder startLocationIcao(@Nullable String value) {
            this.startLocationIcao = value;
            return this;
        }

        public Builder ldgLocationIcao(@Nullable String value) {
            this.ldgLocationIcao = value;
            return this;
        }

        public Builder aircraftHomebase(@Nullable String value) {
            this.aircraftHomebase = value;
            return this;
        }

        public Builder aircraftPresent(boolean value) {
            this.aircraftPresent = value;
            return this;
        }

        public Builder towFlightTypeCode(@Nullable String value) {
            this.towFlightTypeCode = value;
            return this;
        }

        public Builder towedFlightTypeCodes(List<String> value) {
            this.towedFlightTypeCodes = new ArrayList<>(value);
            return this;
        }

        public Builder crew(List<MatchableCrew> value) {
            this.crew = new ArrayList<>(value);
            return this;
        }

        public Builder flightCostBalanceTypeId(int value) {
            this.flightCostBalanceTypeId = value;
            return this;
        }

        public Builder flightCostInvoiceRecipient(@Nullable Recipient value) {
            this.flightCostInvoiceRecipient = value;
            return this;
        }

        public Builder pilot(@Nullable Recipient value) {
            this.pilot = value;
            return this;
        }

        public Builder flightDurationSeconds(int value) {
            this.flightDurationSeconds = value;
            return this;
        }

        public Builder nrOfLdgs(@Nullable Integer value) {
            this.nrOfLdgs = value;
            return this;
        }

        public Builder nrOfLdgsOnStartLocation(@Nullable Integer value) {
            this.nrOfLdgsOnStartLocation = value;
            return this;
        }

        public Builder noStartTimeInformation(boolean value) {
            this.noStartTimeInformation = value;
            return this;
        }

        public Builder noLdgTimeInformation(boolean value) {
            this.noLdgTimeInformation = value;
            return this;
        }

        public MatchableFlight build() {
            return new MatchableFlight(this);
        }
    }
}
