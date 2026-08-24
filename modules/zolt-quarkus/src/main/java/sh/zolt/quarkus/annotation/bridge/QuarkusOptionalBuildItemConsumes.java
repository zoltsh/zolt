package sh.zolt.quarkus.annotation.bridge;

import sh.zolt.quarkus.annotation.ZoltQuarkusTestClassBeanCustomizer;
import io.quarkus.builder.BuildStepBuilder;
import io.quarkus.builder.item.BuildItem;
import io.quarkus.builder.item.SimpleBuildItem;
import java.util.List;
import java.util.Optional;

public final class QuarkusOptionalBuildItemConsumes {
    private QuarkusOptionalBuildItemConsumes() {
    }

    public static BuildStepBuilder optional(
            BuildStepBuilder step,
            List<Optional<Class<? extends SimpleBuildItem>>> buildItemClasses) {
        buildItemClasses.forEach(buildItemClass -> buildItemClass.ifPresent(itemClass -> optional(step, itemClass)));
        return step;
    }

    public static BuildStepBuilder optional(
            BuildStepBuilder step,
            Class<? extends BuildItem> buildItemClass) {
        return optional(step, buildItemClass, step.getClass().getClassLoader());
    }

    static BuildStepBuilder optional(
            BuildStepBuilder step,
            Class<? extends BuildItem> buildItemClass,
            ClassLoader classLoader) {
        try {
            Class<?> consumeFlagClass = Class.forName("io.quarkus.builder.ConsumeFlag", false, classLoader);
            Class<?> consumeFlagsClass = Class.forName("io.quarkus.builder.ConsumeFlags", false, classLoader);
            Object optional = enumValue(consumeFlagClass, "OPTIONAL");
            Object consumeFlags = consumeFlagsClass.getMethod("of", consumeFlagClass).invoke(null, optional);
            step.getClass()
                    .getMethod("consumes", Class.class, consumeFlagsClass)
                    .invoke(step, buildItemClass, consumeFlags);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            ZoltQuarkusTestClassBeanCustomizer.writeDiagnostic(
                    buildItemClass.getSimpleName() + ".optionalConsume=false",
                    buildItemClass.getSimpleName() + ".optionalConsumeReason=" + exception.getClass().getSimpleName());
        }
        return step;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> enumClass, String value) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), value);
    }
}
