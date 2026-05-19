package ch.alpenflight.referencedata.domain;

import ch.alpenflight.platform.id.CountryId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * ISO-3166 country reference row. System-global; not tenant-scoped.
 * Mapped to the V2 {@code country} table; data lives in the V2 Flyway
 * seed. No setters, no mutating methods — the API does not write here.
 */
@Entity
@Table(name = "country")
public class Country {

    @Id
    private @Nullable UUID id;

    @Column(name = "iso2_code", nullable = false, length = 2)
    private String iso2Code = "";

    @Column(name = "iso3_code", nullable = false, length = 3)
    private String iso3Code = "";

    @Column(name = "name", nullable = false, length = 100)
    private String name = "";

    @Column(name = "full_name", length = 250)
    private @Nullable String fullName;

    protected Country() {
        // JPA.
    }

    public @Nullable CountryId getId() {
        return CountryId.ofNullable(id);
    }

    public @Nullable UUID getRawId() {
        return id;
    }

    public String getIso2Code() {
        return iso2Code;
    }

    public String getIso3Code() {
        return iso3Code;
    }

    public String getName() {
        return name;
    }

    public @Nullable String getFullName() {
        return fullName;
    }
}
