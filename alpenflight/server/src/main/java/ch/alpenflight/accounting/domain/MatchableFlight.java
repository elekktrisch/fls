package ch.alpenflight.accounting.domain;

import ch.alpenflight.accounting.domain.RuleBasedDeliveryDetails.Recipient;
import ch.alpenflight.flights.domain.FlightAircraftType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class MatchableFlight {

    private static final MathContext DOTNET_DOUBLE_TO_DECIMAL =
            new MathContext(15, RoundingMode.HALF_EVEN);

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
    private final @Nullable String instructorDisplayName;

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
        this.instructorDisplayName = builder.instructorDisplayName;
    }

    public static Builder builder(FlightAircraftType flightAircraftType) {
        return new Builder(flightAircraftType);
    }

    public int flightAircraftTypeBit() {
        return flightAircraftType.legacyId();
    }

    public @Nullable String immatriculation() {
        return immatriculation;
    }

    public @Nullable String flightTypeCode() {
        return flightTypeCode;
    }

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

    public int flightCostBalanceTypeId() {
        return flightCostBalanceTypeId;
    }

    public @Nullable Recipient flightCostInvoiceRecipient() {
        return flightCostInvoiceRecipient;
    }

    public @Nullable Recipient pilot() {
        return pilot;
    }

    public int flightDurationSeconds() {
        return flightDurationSeconds;
    }

    public BigDecimal flightDurationMinutes() {
        return new BigDecimal(flightDurationSeconds / 60.0, DOTNET_DOUBLE_TO_DECIMAL);
    }

    public @Nullable String instructorDisplayName() {
        return instructorDisplayName;
    }

    public @Nullable Integer nrOfLdgs() {
        return nrOfLdgs;
    }

    public @Nullable Integer nrOfLdgsOnStartLocation() {
        return nrOfLdgsOnStartLocation;
    }

    public boolean noStartTimeInformation() {
        return noStartTimeInformation;
    }

    public boolean noLdgTimeInformation() {
        return noLdgTimeInformation;
    }

    public boolean isGlider() {
        return flightAircraftType == FlightAircraftType.GLIDER;
    }

    public boolean isTow() {
        return flightAircraftType == FlightAircraftType.TOW;
    }

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
                .instructorDisplayName(instructorDisplayName)
                .build();
    }

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
        private @Nullable String instructorDisplayName;

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

        public Builder instructorDisplayName(@Nullable String value) {
            this.instructorDisplayName = value;
            return this;
        }

        public MatchableFlight build() {
            return new MatchableFlight(this);
        }
    }
}
