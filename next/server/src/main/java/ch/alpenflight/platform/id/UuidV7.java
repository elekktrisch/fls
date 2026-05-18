package ch.alpenflight.platform.id;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hibernate.annotations.IdGeneratorType;

/**
 * Marks an entity's identifier field as application-side UUID v7. Hibernate's
 * {@link IdGeneratorType} meta-annotation routes through
 * {@link FlsUuidV7Generator}; no `@GeneratedValue` companion is needed (or
 * permitted) on the field.
 */
@IdGeneratorType(FlsUuidV7Generator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface UuidV7 {
}
