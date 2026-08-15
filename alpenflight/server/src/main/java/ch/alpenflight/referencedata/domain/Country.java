package ch.alpenflight.referencedata.domain;

import ch.alpenflight.platform.id.CountryId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "t_country")
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
    }

    public @Nullable CountryId getId() {
        return CountryId.ofNullable(id);
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
