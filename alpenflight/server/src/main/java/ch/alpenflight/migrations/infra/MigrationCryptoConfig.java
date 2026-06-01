package ch.alpenflight.migrations.infra;

import ch.alpenflight.migration.bundle.crypto.MigrationBundleCipher;
import ch.alpenflight.migration.bundle.crypto.TinkMigrationBundleCipher;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * S-140 master-keyset bootstrap. Reads the env-pinned Tink JSON keyset
 * once at startup, instantiates an {@link Aead} primitive bound to it,
 * and publishes the primitive as a Spring bean. Reused by S-141 for
 * bundle decryption (StreamingAead can hang off the same {@link KeysetHandle}).
 *
 * <p>Sourcing:
 * <ul>
 *   <li>{@code ALPENFLIGHT_MIGRATION_MASTER_KEYSET_SOURCE = inline}
 *       (default) — {@code ALPENFLIGHT_MIGRATION_MASTER_KEYSET} is a
 *       base64-encoded Tink JSON keyset.</li>
 *   <li>{@code ALPENFLIGHT_MIGRATION_MASTER_KEYSET_SOURCE = file} —
 *       the env var is a filesystem path to the raw Tink JSON keyset.</li>
 * </ul>
 *
 * <p>Generate a fresh keyset locally:
 * <pre>{@code
 *   tinkey create-keyset --key-template AES256_GCM --out keyset.json
 *   base64 -w0 keyset.json   # → ALPENFLIGHT_MIGRATION_MASTER_KEYSET=<value>
 * }</pre>
 *
 * <p>Fail-fast on missing / malformed: any error during
 * {@link #migrationMasterKeyAead} surfaces as a {@code BeanCreationException}
 * at app start.
 */
@Configuration
public class MigrationCryptoConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationCryptoConfig.class);

    /**
     * Source mode for the keyset. {@link #INLINE} + {@link #FILE} are the
     * production-safe shapes (env-pinned material); {@link #EPHEMERAL} is
     * the dev / smoke-bootstrap escape hatch — a fresh keyset is generated
     * at startup, logged loudly, and discarded on JVM exit. Any production
     * deployment using {@code EPHEMERAL} would lose every previously-issued
     * row's private key on the next restart; the warning log says so.
     */
    public enum Source { INLINE, FILE, EPHEMERAL }

    private final Source source;
    private final String value;
    private final boolean allowEphemeralInProd;
    private final Environment environment;

    public MigrationCryptoConfig(
            @Value("${alpenflight.migration.master-keyset.source:INLINE}") Source source,
            @Value("${alpenflight.migration.master-keyset.value:}") String value,
            @Value("${alpenflight.migration.master-keyset.allow-ephemeral-in-prod:false}")
                    boolean allowEphemeralInProd,
            Environment environment) {
        this.source = source;
        this.value = value;
        this.allowEphemeralInProd = allowEphemeralInProd;
        this.environment = environment;
    }

    /**
     * Master keyset is loaded ONCE here; rotation requires a rolling JVM
     * restart (the bean is constructed at {@code @Bean} factory time and
     * cached for the app lifetime). Active-rotation-without-restart is a
     * future story.
     */
    @Bean
    Aead migrationMasterKeyAead() throws GeneralSecurityException, IOException {
        AeadConfig.register();
        if (source == Source.EPHEMERAL
                && Arrays.asList(environment.getActiveProfiles()).contains("prod")
                && !allowEphemeralInProd) {
            throw new IllegalStateException(
                    "alpenflight.migration.master-keyset.source=EPHEMERAL is forbidden under the "
                            + "prod profile — an ephemeral keyset would wipe every in-flight "
                            + "private key on restart. Set ALPENFLIGHT_MIGRATION_MASTER_KEYSET to "
                            + "a base64-encoded Tink JSON keyset (or source=FILE + path). "
                            + "Tests that need to boot under the prod profile without a real "
                            + "keyset may set alpenflight.migration.master-keyset.allow-ephemeral-in-prod=true.");
        }
        KeysetHandle handle = switch (source) {
            case EPHEMERAL -> {
                KeysetHandle generated = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM);
                LOG.warn("MigrationCryptoConfig: source=EPHEMERAL — generated a one-shot master "
                        + "keyset (primaryKeyId={}). Every restart wipes previously-issued private "
                        + "keys. Dev / test only.", generated.getPrimary().getId());
                yield generated;
            }
            case INLINE, FILE -> {
                if (value.isBlank()) {
                    throw new IllegalStateException(
                            "Required env ALPENFLIGHT_MIGRATION_MASTER_KEYSET is missing or blank. "
                                    + "Generate a keyset with `tinkey create-keyset --key-template "
                                    + "AES256_GCM --out keyset.json` then base64-encode the file. "
                                    + "Dev profile may set source=EPHEMERAL instead.");
                }
                String json = readKeysetJson();
                yield TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get());
            }
        };
        Aead aead = handle.getPrimitive(RegistryConfiguration.get(), Aead.class);
        LOG.info("MigrationCryptoConfig: master keyset ready source={} primaryKeyId={}",
                source, handle.getPrimary().getId());
        return aead;
    }

    /**
     * S-139: {@link TinkMigrationBundleCipher} moved to the
     * {@code migration-bundle} module (Spring-free, for standalone-jar reuse),
     * so it is no longer a {@code @Component}. Declare it explicitly here so
     * {@code MigrationBundleIngestService}'s {@link MigrationBundleCipher}
     * constructor injection still resolves.
     */
    @Bean
    MigrationBundleCipher migrationBundleCipher() {
        return new TinkMigrationBundleCipher();
    }

    private String readKeysetJson() throws IOException {
        return switch (source) {
            case INLINE -> {
                try {
                    yield new String(
                            Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(
                            "ALPENFLIGHT_MIGRATION_MASTER_KEYSET is not valid base64", e);
                }
            }
            case FILE -> Files.readString(Path.of(value), StandardCharsets.UTF_8);
            case EPHEMERAL -> throw new IllegalStateException(
                    "readKeysetJson() not reachable for EPHEMERAL source");
        };
    }

}
