/**
 * Spring Data JPA implementation of
 * {@link ch.alpenflight.migrations.domain.MigrationUploadRepository} and
 * Google Tink AEAD adapter for
 * {@link ch.alpenflight.migrations.domain.MigrationCryptoService}.
 *
 * <p>{@code MigrationCryptoConfig} loads the master keyset from the
 * {@code ALPENFLIGHT_MIGRATION_MASTER_KEYSET} env var at
 * {@code @PostConstruct} time — fail-fast on missing / malformed
 * (BeanCreationException at app start). The {@code Aead} bean is reused
 * by S-141 for bundle decryption.
 */
@org.jspecify.annotations.NullMarked
package ch.alpenflight.migrations.infra;
