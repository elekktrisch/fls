package ch.alpenflight.migration.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Producer-binding contract for the SELECT half of {@link MapperLegacyBindings}
 * (the legacy-export side consumed by the {@code S-139} export jar +
 * {@code ProducerHarness}).
 *
 * <p>The load-bearing invariant the registry Javadoc promises is "a legacy
 * column rename is caught by a {@code SELECT} failure rather than a silent
 * {@code NULL}": every legacy ResultSet column a mapper's {@code writeNdjson}
 * reads must be projected by the bound SELECT. This test pins that for the
 * tenant-scoped FLIGHT-group pair LOCATION + INOUTBOUND_POINT (T-02c) — they
 * were unbound before, so the export jar threw "No legacy binding registered"
 * for them.
 *
 * <p>Verified against the legacy MSSQL FLSTest schema
 * ({@code flsserver/database/FLSTest/2 alter/2 Alter Database.sql} +
 * {@code DBUpdate_v1.8.0.sql}): {@code Locations} carries no own
 * {@code ClubId} — it is the fan-out partner Club aliased producer-side per
 * {@link ch.alpenflight.migration.bundle.flight.LocationMapper}'s contract,
 * so it is asserted as an alias rather than a base table column.
 */
class MapperLegacyBindingsTest {

    /**
     * Every legacy ResultSet column {@code LocationMapper.writeNdjson} reads.
     * {@code ClubId} is the fan-out partner (no base-table column) — projected
     * by the SELECT's join, aliased on the cursor.
     */
    private static final List<String> LOCATION_LEGACY_COLUMNS = List.of(
            "LocationId", "ClubId", "LocationName", "LocationShortName",
            "CountryId", "LocationTypeId", "IcaoCode", "Latitude", "Longitude",
            "Elevation", "ElevationUnitType", "RunwayDirection", "RunwayLength",
            "RunwayLengthUnitType", "AirportFrequency", "Description",
            "SortIndicator", "IsInboundRouteRequired", "IsOutboundRouteRequired",
            "IsFastEntryRecord", "CreatedOn", "CreatedByUserId", "ModifiedOn",
            "ModifiedByUserId", "DeletedOn", "DeletedByUserId");

    /**
     * Every legacy ResultSet column {@code InOutboundPointMapper.writeNdjson}
     * reads. {@code ClubId} is the child's own fan-out partner club (the child
     * fans out across its parent Location's partner set, exactly like LOCATION) —
     * no base-table column, projected via the SELECT's join, aliased on the cursor.
     */
    private static final List<String> INOUTBOUND_POINT_LEGACY_COLUMNS = List.of(
            "InOutboundPointId", "LocationId", "ClubId", "InOutboundPointName",
            "IsInboundPoint", "IsOutboundPoint", "CreatedOn", "CreatedByUserId",
            "ModifiedOn", "ModifiedByUserId", "DeletedOn", "DeletedByUserId");

    /**
     * Every legacy {@code Persons} ResultSet column {@code PersonMapper.writeNdjson}
     * reads (J-0c T-21). {@code Persons} is cross-tenant (no {@code ClubId}); its
     * only outgoing FK is the nullable {@code CountryId} → {@code Countries},
     * resolved via the SYSTEM_GLOBAL COUNTRY id-map (T-15 mechanism), so the
     * column is projected verbatim and {@code PersonMapper.foreignKeys()} declares
     * COUNTRY. Note the British-spelled {@code LicenceNumber} (added by
     * DBUpdate_v1.8.1) and {@code Has*Licence}/{@code Has*StartPermission} flags
     * the later DBUpdate scripts add — the SELECT must project the names the
     * mapper actually reads, not the v1.0 base-schema names.
     */
    private static final List<String> PERSON_LEGACY_COLUMNS = List.of(
            "PersonId", "Lastname", "Firstname", "Midname", "CompanyName",
            "AddressLine1", "AddressLine2", "Zip", "City", "Region", "CountryId",
            "PrivatePhone", "MobilePhone", "BusinessPhone", "FaxNumber",
            "EmailPrivate", "EmailBusiness", "PreferMailToBusinessMail", "Birthday",
            "HasMotorPilotLicence", "HasTowPilotLicence", "HasGliderInstructorLicence",
            "HasGliderPilotLicence", "HasGliderTraineeLicence", "HasGliderPAXLicence",
            "HasTMGLicence", "HasWinchOperatorLicence", "HasMotorInstructorLicence",
            "HasPartMLicence", "LicenceNumber", "MedicalClass1ExpireDate",
            "MedicalClass2ExpireDate", "MedicalLaplExpireDate",
            "GliderInstructorLicenceExpireDate", "MotorInstructorLicenceExpireDate",
            "PartMLicenceExpireDate", "HasGliderTowingStartPermission",
            "HasGliderSelfStartPermission", "HasGliderWinchStartPermission",
            "SpotLink", "ReceiveOwnedAircraftStatisticReports", "EnableAddress",
            "IsFastEntryRecord", "CreatedOn", "CreatedByUserId", "ModifiedOn",
            "ModifiedByUserId", "DeletedOn", "DeletedByUserId");

    @Test
    void locationIsRegistered() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.LOCATION))
                .as("LOCATION must be bound so the export jar / ProducerHarness "
                        + "stop throwing \"No legacy binding registered\"")
                .isTrue();
    }

    @Test
    void inOutboundPointIsRegistered() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.INOUTBOUND_POINT))
                .as("INOUTBOUND_POINT must be bound — it is the Location aggregate child")
                .isTrue();
    }

    @Test
    void locationIsTenantScopedFullPort() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.LOCATION))
                .as("Location is FULL_PORT per V7 (club_id IS the @TenantId)")
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void inOutboundPointIsFullPort() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.INOUTBOUND_POINT))
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void locationSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.LOCATION);
        for (String legacyColumn : LOCATION_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("LocationMapper.writeNdjson reads %s from the ResultSet — the "
                            + "bound SELECT must project it (else: silent NULL)", legacyColumn)
                    .contains(legacyColumn);
        }
    }

    @Test
    void locationSelectAliasesTheFanOutPartnerClubId() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.LOCATION).toUpperCase();
        // Legacy Locations has no own ClubId; the producer fans out one row per
        // referencing Club (Clubs.HomebaseId + Flights start/ldg via OwnerId),
        // aliasing the partner Club id as the cursor's ClubId column.
        assertThat(select)
                .as("the fan-out partner Club must be aliased AS ClubId on the cursor")
                .contains("AS CLUBID");
    }

    @Test
    void locationSelectProjectsTheLocationTypeIntCupIdNotTheGuidFk() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.LOCATION).toUpperCase();
        // Legacy Locations.LocationTypeId is a uniqueidentifier (GUID FK to
        // LocationTypes), but LocationMapper.writeNdjson reads it via getInt +
        // legacyIntIdToUuidString — the legacy_int_id resolution expects the int
        // LocationTypeCupId (== t_location_type.legacy_int_id), which lives on
        // LocationTypes, not on Locations. The producer must JOIN LocationTypes
        // and project the int CupId AS LocationTypeId, else getInt throws
        // "conversion from uniqueidentifier to INTEGER is unsupported" (J-0c T-14).
        assertThat(select)
                .as("SELECT must JOIN LocationTypes to source the int CupId")
                .contains("LOCATIONTYPES");
        assertThat(select)
                .as("SELECT must project LocationTypeCupId aliased AS LocationTypeId "
                        + "so writeNdjson's getInt reads the int code, not the GUID")
                .contains("LOCATIONTYPECUPID AS LOCATIONTYPEID");
    }

    @Test
    void inOutboundPointSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.INOUTBOUND_POINT);
        for (String legacyColumn : INOUTBOUND_POINT_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("InOutboundPointMapper.writeNdjson reads %s — the bound SELECT "
                            + "must project it", legacyColumn)
                    .contains(legacyColumn);
        }
    }

    @Test
    void inOutboundPointSelectFansOutOverTheSameParentPartnerSetAsLocation() {
        String iop = MapperLegacyBindings.selectForProducer(EntityType.INOUTBOUND_POINT)
                .toUpperCase();
        // The child fans out one row per (legacy IOP, partner club), joining its
        // parent Location's fan-out partner set — the SAME union the LOCATION
        // binding uses — and aliasing the partner Club id AS ClubId so the child
        // carries its OWN legacy club on the wire (resolver-only) for T-07's
        // composite (location_id, club_id) FK lookup.
        assertThat(iop)
                .as("child must alias its own fan-out partner Club AS ClubId")
                .contains("AS CLUBID");
        assertThat(iop)
                .as("child fan-out joins the parent Location partner set "
                        + "(Clubs.HomebaseId + Flights start/landing via OwnerId)")
                .contains("HOMEBASEID")
                .contains("STARTLOCATIONID")
                .contains("LDGLOCATIONID");
    }

    @Test
    void selectTargetsTheLegacyTablesByName() {
        assertThat(MapperLegacyBindings.selectForProducer(EntityType.LOCATION))
                .as("base table is the legacy Locations table")
                .contains("Locations");
        assertThat(MapperLegacyBindings.selectForProducer(EntityType.INOUTBOUND_POINT))
                .contains("InOutboundPoints");
    }

    @Test
    void personIsRegistered() {
        assertThat(MapperLegacyBindings.isRegistered(EntityType.PERSON))
                .as("PERSON must be bound so the producer exports t_person and "
                        + "USER.person_id (a passed-through legacy GUID) resolves "
                        + "against fk_user_person_id at ingest (J-0c T-21)")
                .isTrue();
    }

    @Test
    void personIsFullPort() {
        assertThat(MapperLegacyBindings.portPolicy(EntityType.PERSON))
                .as("Person is the cross-tenant FULL_PORT aggregate root (ADR 0008)")
                .isEqualTo(MapperLegacyBindings.PortPolicy.FULL_PORT);
    }

    @Test
    void personSelectProjectsEveryColumnTheMapperReads() {
        String select = MapperLegacyBindings.selectForProducer(EntityType.PERSON);
        for (String legacyColumn : PERSON_LEGACY_COLUMNS) {
            assertThat(select)
                    .as("PersonMapper.writeNdjson reads %s from the ResultSet — the "
                            + "bound SELECT must project it (else: silent NULL)", legacyColumn)
                    .contains(legacyColumn);
        }
    }

    @Test
    void personSelectTargetsTheLegacyPersonsTable() {
        assertThat(MapperLegacyBindings.selectForProducer(EntityType.PERSON))
                .as("base table is the legacy Persons table")
                .contains("Persons");
    }

    @Test
    void personHasNoConsumerInsertOnlyWriteAccessIsValidated() {
        // PERSON is FULL_PORT, so it carries a real INSERT (unlike SYSTEM_GLOBAL
        // entries whose consumer half is empty). Cross-tenant: t_person has NO
        // club_id column, so the INSERT must not reference one.
        String insert = MapperLegacyBindings.insertForConsumer(EntityType.PERSON);
        assertThat(insert)
                .as("PERSON FULL_PORT consumer INSERT targets t_person")
                .contains("INSERT INTO t_person");
        assertThat(insert.toLowerCase(java.util.Locale.ROOT))
                .as("t_person is cross-tenant — the INSERT must not bind a club_id")
                .doesNotContain("club_id");
    }

    @Test
    void unregisteredEntityStillFailsLoudly() {
        // Guard the fail-closed contract survives the registry growth.
        assertThatThrownBy(() -> MapperLegacyBindings.require(EntityType.AIRCRAFT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No legacy binding registered");
    }
}
