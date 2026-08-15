package ch.alpenflight.referencedata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

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
    }

    public @Nullable UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
