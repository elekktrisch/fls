package ch.alpenflight.platform.persistence;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a JPA-persisted column that records the principal who effected a
 * mutation but is never read back through the aggregate's API (e.g.
 * {@code deletedByUserId} on a soft-deletable aggregate root). The value is
 * still load-bearing — Hibernate writes it, audit / forensics tooling reads
 * the column directly via JDBC — but the aggregate itself has no business
 * reason to surface the actor through a getter.
 *
 * <p>The write-only shape confuses two static checkers:
 *
 * <ul>
 *   <li>ErrorProne's {@code UnusedVariable} flags the field as dead because
 *       no reader exists in source.</li>
 *   <li>IntelliJ's {@code FieldCanBeLocal} suggests demoting to a local
 *       because no other method reads it.</li>
 * </ul>
 *
 * Both are wrong here: the persistence layer is the implicit reader.
 *
 * <p><strong>Suppression is not transitive through this meta-annotation.</strong>
 * The JLS scopes {@code @SuppressWarnings} to the directly-annotated element,
 * and neither IntelliJ nor ErrorProne walks meta-annotations for suppression.
 * Every usage MUST pair {@code @PersistedAuditActor} with an explicit
 * {@code @SuppressWarnings({"UnusedVariable", "FieldCanBeLocal"})} on the
 * field — the marker carries the WHY; the bare {@code @SuppressWarnings}
 * actually silences the checker.
 *
 * <p>Use sparingly: only for fields that are genuinely write-only through
 * the aggregate. Anything readable through the API gets a normal getter and
 * doesn't need this marker.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface PersistedAuditActor {}
