package ch.alpenflight.buildgates;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.alpenflight.platform.status.SystemStatusController;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClockInjectedGateTest {

    private static final Set<String> NO_ARG_NOW_OWNER_TYPES = Set.of(
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.LocalTime",
            "java.time.OffsetDateTime",
            "java.time.OffsetTime",
            "java.time.Year",
            "java.time.YearMonth",
            "java.time.ZonedDateTime");

    private static final ArchCondition<JavaClass> NEVER_CALL_NO_ARG_NOW = new ArchCondition<>(
            "never call the no-arg now() overload on a java.time type") {
        @Override
        public void check(JavaClass javaClass, ConditionEvents events) {
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                AccessTarget.MethodCallTarget target = call.getTarget();
                boolean callsNow = target.getName().equals("now");
                boolean noArguments = target.getRawParameterTypes().isEmpty();
                boolean onAClockLikeType = NO_ARG_NOW_OWNER_TYPES.contains(target.getOwner().getFullName());
                if (callsNow && noArguments && onAClockLikeType) {
                    events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                }
            }
        }
    };

    @Test
    void productionCodeNeverCallsNoArgNowAndInjectsTheClockInstead() {
        JavaClasses classes = new ClassFileImporter().importPackages(
                "ch.alpenflight.platform", "ch.alpenflight.core", "ch.alpenflight.modulesopen", "ch.alpenflight.modulespro");

        assertThat(classes.contain(SystemStatusController.class))
                .as("Gate 8 (Clock injected) production scan must include its existing pass-case, "
                        + "SystemStatusController")
                .isTrue();
        assertThatNoException().as("Gate 8 (Clock injected)").isThrownBy(() -> rule().check(classes));
    }

    @Test
    void fixtureDirectNowCallIsCaught() {
        JavaClasses classes =
                new ClassFileImporter().importPackages("ch.alpenflight.buildgates.clockinjectedgate.fixtures");

        assertThatThrownBy(() -> rule().check(classes)).hasMessageContaining("Gate 8");
    }

    private static ArchRule rule() {
        return classes()
                .should(NEVER_CALL_NO_ARG_NOW)
                .as("Gate 8 (Clock injected): no class calls the no-arg now() overload on Instant, "
                        + "LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, Year, "
                        + "YearMonth, or ZonedDateTime; the Clock is injected instead");
    }
}
