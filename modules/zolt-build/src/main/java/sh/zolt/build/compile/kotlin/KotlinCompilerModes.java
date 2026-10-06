package sh.zolt.build.compile.kotlin;

/** Closed Kotlin compiler modes retained as typed policy rather than raw strings. */
public final class KotlinCompilerModes {
    private KotlinCompilerModes() {
    }

    /** A mode value as accepted by the Kotlin compiler argument protocol. */
    public interface Value {
        String argumentValue();

        default boolean configured() {
            return !argumentValue().isEmpty();
        }
    }

    public enum JvmDefaultMode implements Value {
        UNSPECIFIED(""),
        ENABLE("enable"),
        NO_COMPATIBILITY("no-compatibility"),
        DISABLE("disable");

        private final String argumentValue;

        JvmDefaultMode(String argumentValue) {
            this.argumentValue = argumentValue;
        }

        @Override
        public String argumentValue() {
            return argumentValue;
        }
    }

    public enum ExplicitApiMode implements Value {
        UNSPECIFIED(""),
        STRICT("strict"),
        WARNING("warning"),
        DISABLE("disable");

        private final String argumentValue;

        ExplicitApiMode(String argumentValue) {
            this.argumentValue = argumentValue;
        }

        @Override
        public String argumentValue() {
            return argumentValue;
        }
    }

    public enum StringConcatMode implements Value {
        UNSPECIFIED(""),
        INDY_WITH_CONSTANTS("indy-with-constants"),
        INDY("indy"),
        INLINE("inline");

        private final String argumentValue;

        StringConcatMode(String argumentValue) {
            this.argumentValue = argumentValue;
        }

        @Override
        public String argumentValue() {
            return argumentValue;
        }
    }

    public enum ClosureGenerationMode implements Value {
        UNSPECIFIED(""),
        CLASS("class"),
        INDY("indy");

        private final String argumentValue;

        ClosureGenerationMode(String argumentValue) {
            this.argumentValue = argumentValue;
        }

        @Override
        public String argumentValue() {
            return argumentValue;
        }
    }

    public enum AnnotationDefaultTargetMode implements Value {
        UNSPECIFIED(""),
        FIRST_ONLY("first-only"),
        FIRST_ONLY_WARN("first-only-warn"),
        PARAM_PROPERTY("param-property");

        private final String argumentValue;

        AnnotationDefaultTargetMode(String argumentValue) {
            this.argumentValue = argumentValue;
        }

        @Override
        public String argumentValue() {
            return argumentValue;
        }
    }

    public enum AssertionMode implements Value {
        UNSPECIFIED(""),
        ALWAYS_ENABLE("always-enable"),
        ALWAYS_DISABLE("always-disable"),
        JVM("jvm"),
        LEGACY("legacy");

        private final String argumentValue;

        AssertionMode(String argumentValue) {
            this.argumentValue = argumentValue;
        }

        @Override
        public String argumentValue() {
            return argumentValue;
        }
    }

    public enum ReturnValueCheckerMode implements Value {
        UNSPECIFIED(""),
        CHECK("check"),
        FULL("full"),
        DISABLE("disable");

        private final String argumentValue;

        ReturnValueCheckerMode(String argumentValue) {
            this.argumentValue = argumentValue;
        }

        @Override
        public String argumentValue() {
            return argumentValue;
        }
    }

    public enum NullabilityMode implements Value {
        UNSPECIFIED(""),
        IGNORE("ignore"),
        WARN("warn"),
        STRICT("strict");

        private final String argumentValue;

        NullabilityMode(String argumentValue) {
            this.argumentValue = argumentValue;
        }

        @Override
        public String argumentValue() {
            return argumentValue;
        }
    }

    public enum CompatqualAnnotationsMode implements Value {
        UNSPECIFIED(""),
        ENABLE("enable"),
        DISABLE("disable");

        private final String argumentValue;

        CompatqualAnnotationsMode(String argumentValue) {
            this.argumentValue = argumentValue;
        }

        @Override
        public String argumentValue() {
            return argumentValue;
        }
    }

    public enum AbiStabilityMode implements Value {
        UNSPECIFIED(""),
        STABLE("stable"),
        UNSTABLE("unstable");

        private final String argumentValue;

        AbiStabilityMode(String argumentValue) {
            this.argumentValue = argumentValue;
        }

        @Override
        public String argumentValue() {
            return argumentValue;
        }
    }
}
