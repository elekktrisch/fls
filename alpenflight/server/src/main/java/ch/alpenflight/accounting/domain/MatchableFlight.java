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

    private MatchableFlight(Builder builder) {
        this.flightAircraftType = builder.flightAircraftType;
        this.immatriculation = builder.immatriculation;
        this.flightTypeCode = builder.flightTypeCode;
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

        public MatchableFlight build() {
            return new MatchableFlight(this);
        }
    }
}
