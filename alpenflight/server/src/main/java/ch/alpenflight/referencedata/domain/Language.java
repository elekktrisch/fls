package ch.alpenflight.referencedata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Language reference row (BCP-47 code + display name). System-global; not
 * tenant-scoped. Mapped to the V2 {@code t_language} table; data lives in
 * the V2 Flyway seed (eight rows, never mutated at runtime). No setters,
 * no mutating methods — the API does not write here.
 *
 * <p>First JPA mapping of {@code t_language} (ADR 0027 per-touch JDBC
 * retirement; introduced for {@code me.application.MeService}'s
 * language-code projection). {@code platform.tenancy.LanguageCodeLookup}
 * deliberately stays JDBC — it runs in Hibernate's session-open path and
 * must not recurse into JPA.
 */
@Entity
@Table(name = "t_language")
public class Language {

    @Id
    private @Nullable UUID id;

    @Column(name = "code", nullable = false, length = 10)
    private String code = "";

    @Column(name = "name", nullable = false, length = 50)
    private String name = "";

    protected Language() {
        // JPA.
    }

    public @Nullable UUID getId() {
        return id;
    }

    /** BCP-47 code, e.g. {@code en}, {@code de}. Unique ({@code ux_language_code}). */
    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
