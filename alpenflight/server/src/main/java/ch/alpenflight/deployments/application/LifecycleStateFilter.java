package ch.alpenflight.deployments.application;

import ch.alpenflight.deployments.domain.LifecycleState;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface LifecycleStateFilter {

    LifecycleState[] value();
}
