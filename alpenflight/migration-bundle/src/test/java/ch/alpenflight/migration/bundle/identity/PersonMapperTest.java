package ch.alpenflight.migration.bundle.identity;

import static org.assertj.core.api.Assertions.assertThat;

import ch.alpenflight.migration.bundle.AbstractMapperContractTest;
import ch.alpenflight.migration.bundle.EntityType;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.datafaker.Faker;
import org.junit.jupiter.api.Test;

class PersonMapperTest extends AbstractMapperContractTest<PersonMapper> {

    private final PersonMapper mapper = new PersonMapper();

    @Override
    protected PersonMapper mapper() {
        return mapper;
    }

    @Override
    protected Map<String, Object> legacyRow(Faker faker) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("PersonId", randomUuidString(faker));
        row.put("Lastname", faker.name().lastName());
        row.put("Firstname", faker.name().firstName());
        row.put("Midname", faker.name().firstName());
        row.put("CompanyName", faker.company().name());
        row.put("AddressLine1", faker.address().streetAddress());
        row.put("AddressLine2", faker.address().secondaryAddress());
        row.put("Zip", faker.address().zipCode());
        row.put("City", faker.address().city());
        row.put("Region", faker.address().state());
        row.put("CountryId", randomUuidString(faker));
        row.put("PrivatePhone", faker.phoneNumber().phoneNumber());
        row.put("MobilePhone", faker.phoneNumber().cellPhone());
        row.put("BusinessPhone", faker.phoneNumber().phoneNumber());
        row.put("FaxNumber", faker.phoneNumber().phoneNumber());
        row.put("EmailPrivate", faker.internet().emailAddress());
        row.put("EmailBusiness", faker.internet().emailAddress());
        row.put("PreferMailToBusinessMail", true);
        row.put("Birthday", Date.valueOf(LocalDate.parse("1990-05-15")));
        row.put("HasMotorPilotLicence", true);
        row.put("HasTowPilotLicence", false);
        row.put("HasGliderInstructorLicence", true);
        row.put("HasGliderPilotLicence", true);
        row.put("HasGliderTraineeLicence", false);
        row.put("HasGliderPAXLicence", true);
        row.put("HasTMGLicence", false);
        row.put("HasWinchOperatorLicence", true);
        row.put("HasMotorInstructorLicence", false);
        row.put("HasPartMLicence", true);
        row.put("LicenceNumber", faker.expression("#{regexify '[A-Z]{2}-[0-9]{6}'}"));
        row.put("MedicalClass1ExpireDate", Date.valueOf(LocalDate.parse("2026-12-31")));
        row.put("MedicalClass2ExpireDate", Date.valueOf(LocalDate.parse("2026-06-30")));
        row.put("MedicalLaplExpireDate", Date.valueOf(LocalDate.parse("2026-03-15")));
        row.put("GliderInstructorLicenceExpireDate", Date.valueOf(LocalDate.parse("2027-01-01")));
        row.put("MotorInstructorLicenceExpireDate", Date.valueOf(LocalDate.parse("2027-06-01")));
        row.put("PartMLicenceExpireDate", Date.valueOf(LocalDate.parse("2027-12-01")));
        row.put("HasGliderTowingStartPermission", true);
        row.put("HasGliderSelfStartPermission", true);
        row.put("HasGliderWinchStartPermission", false);
        row.put("SpotLink", "https://findmespot.com/" + faker.internet().slug());
        row.put("ReceiveOwnedAircraftStatisticReports", true);
        row.put("EnableAddress", true);
        row.put("IsFastEntryRecord", false);
        row.put("CreatedOn", Timestamp.from(Instant.parse("2024-01-01T12:00:00Z")));
        row.put("CreatedByUserId", randomUuidString(faker));
        row.put("ModifiedOn", Timestamp.from(Instant.parse("2024-02-01T12:00:00Z")));
        row.put("ModifiedByUserId", randomUuidString(faker));
        row.put("DeletedOn", Timestamp.from(Instant.parse("2024-03-01T12:00:00Z")));
        row.put("DeletedByUserId", randomUuidString(faker));
        return row;
    }

    @Test
    void exposesPersonEntityType() {
        assertThat(mapper.entityType()).isEqualTo(EntityType.PERSON);
    }

    @Test
    void declaresCountryAsTheOnlyStructuralForeignKey() {
        assertThat(mapper.foreignKeys())
                .as("Person is cross-tenant — Club/PersonClub/User reference Person, "
                        + "not the other way around. Only outgoing structural FK is "
                        + "COUNTRY (SYSTEM_GLOBAL).")
                .containsExactly(EntityType.COUNTRY);
    }

    private static String randomUuidString(Faker faker) {
        return new UUID(faker.random().nextLong(), faker.random().nextLong()).toString();
    }
}
