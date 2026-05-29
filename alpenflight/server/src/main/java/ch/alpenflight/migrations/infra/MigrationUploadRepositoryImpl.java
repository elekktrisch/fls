package ch.alpenflight.migrations.infra;

import ch.alpenflight.migrations.domain.MigrationUpload;
import jakarta.persistence.EntityManager;

/**
 * Custom Spring Data fragment supplying the
 * {@link ch.alpenflight.migrations.domain.MigrationUploadRepository#detachRow}
 * operation that {@link org.springframework.data.jpa.repository.JpaRepository}
 * doesn't expose.
 *
 * <p>Spring Data discovers this class by simple-name + {@code Impl}
 * suffix matching the repository / fragment interface contributing the
 * custom method — here
 * {@link ch.alpenflight.migrations.domain.MigrationUploadRepository}.
 * Constructor injection (rather than {@code @PersistenceContext} field
 * injection) keeps NullAway + the project's "no field injection"
 * convention happy.
 */
class MigrationUploadRepositoryImpl {

    private final EntityManager em;

    MigrationUploadRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    public void detachRow(MigrationUpload row) {
        em.detach(row);
    }
}
