package sh.zolt.manifest.authored;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.ManifestRelativePath;

/** Authored compiler settings with first-class-owned javac arguments rejected. */
public record AuthoredCompiler(
        Optional<String> encoding,
        Optional<JdkApiMode> jdkApi,
        List<String> args,
        Optional<Test> test,
        Optional<Generated> generated) {
    public AuthoredCompiler {
        encoding = nonBlankOptional(encoding, "Compiler encoding");
        jdkApi = Objects.requireNonNull(jdkApi, "Authored compiler JDK API mode must not be null.");
        args = CompilerArguments.copyAndValidate(args, "Main compiler arguments");
        test = Objects.requireNonNull(test, "Authored test compiler settings must not be null.");
        generated = Objects.requireNonNull(
                generated, "Authored generated compiler paths must not be null.");
        if (encoding.isEmpty() && jdkApi.isEmpty() && args.isEmpty()
                && test.isEmpty() && generated.isEmpty()) {
            throw new IllegalArgumentException("Authored compiler settings must not be empty.");
        }
    }

    public enum JdkApiMode {
        RELEASE("release"),
        HOST("host");

        private final String configValue;

        JdkApiMode(String configValue) {
            this.configValue = configValue;
        }

        public String configValue() {
            return configValue;
        }
    }

    /** Authored {@code [compiler.test]} overrides; an omitted mode inherits the main mode. */
    public record Test(Optional<JdkApiMode> jdkApi, List<String> args) {
        public Test {
            jdkApi = Objects.requireNonNull(
                    jdkApi, "Authored test compiler JDK API mode must not be null.");
            args = CompilerArguments.copyAndValidate(args, "Test compiler arguments");
            if (jdkApi.isEmpty() && args.isEmpty()) {
                throw new IllegalArgumentException("Authored test compiler settings must not be empty.");
            }
        }
    }

    /** Annotation-processor source directories relative to {@code [build.output].root}. */
    public record Generated(
            Optional<ManifestRelativePath> main,
            Optional<ManifestRelativePath> test) {
        public Generated {
            main = Objects.requireNonNull(main, "Authored generated main path must not be null.");
            test = Objects.requireNonNull(test, "Authored generated test path must not be null.");
            if (main.isEmpty() && test.isEmpty()) {
                throw new IllegalArgumentException("Authored generated compiler paths must not be empty.");
            }
        }
    }

    private static Optional<String> nonBlankOptional(Optional<String> value, String label) {
        Objects.requireNonNull(value, label + " must not be null.");
        value.ifPresent(item -> {
            ManifestModelValues.requireNonBlank(item, label);
            ManifestModelValues.rejectControlCharacters(item, label);
        });
        return value;
    }

    private static final class CompilerArguments {
        private static final Set<String> FIRST_CLASS_OPTIONS = Set.of(
                "--release",
                "-source",
                "--source",
                "-target",
                "--target",
                "-encoding",
                "-d",
                "-classpath",
                "-cp",
                "--class-path",
                "-bootclasspath",
                "--boot-class-path",
                "-sourcepath",
                "--source-path",
                "--module-source-path",
                "--module-path",
                "-p",
                "--upgrade-module-path",
                "--system",
                "--patch-module",
                "-s",
                "-h");

        /**
         * Annotation processing is Zolt-owned: it emits {@code -proc:none} or {@code -processorpath}
         * from the processor lanes and appends authored args afterwards, where a raw processor flag
         * would silently win. Design §10.4.
         */
        private static final Set<String> PROCESSOR_OPTIONS = Set.of(
                "-processor",
                "-processorpath",
                "--processor-path",
                "--processor-module-path",
                "-proc");

        private CompilerArguments() {}

        static List<String> copyAndValidate(List<String> values, String label) {
            List<String> copy = ManifestModelValues.immutableList(values, label);
            for (String argument : copy) {
                ManifestModelValues.requireNonBlank(argument, label + " entry");
                if (argument.startsWith("@")) {
                    throw new IllegalArgumentException(
                            label + " cannot use javac argument files because they can hide first-class settings: `"
                                    + argument + "`.");
                }
                String option = optionName(argument);
                if (PROCESSOR_OPTIONS.contains(option) || option.startsWith("-proc:")) {
                    throw new IllegalArgumentException(
                            label + " cannot set Zolt-owned javac option `" + option
                                    + "`. Declare annotation processors in [dependencies.processor]"
                                    + " or [dependencies.test-processor] instead.");
                }
                if (FIRST_CLASS_OPTIONS.contains(option) || option.startsWith("-Xbootclasspath")) {
                    throw new IllegalArgumentException(
                            label + " cannot set Zolt-owned javac option `" + option + "`.");
                }
            }
            return copy;
        }

        private static String optionName(String argument) {
            int equals = argument.indexOf('=');
            return equals < 0 ? argument : argument.substring(0, equals);
        }
    }
}
