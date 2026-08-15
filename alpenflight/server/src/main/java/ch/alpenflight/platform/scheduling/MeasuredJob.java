package ch.alpenflight.platform.scheduling;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface MeasuredJob {

    String name();

    String cronShownInConsole() default "";

    String description() default "";
}
