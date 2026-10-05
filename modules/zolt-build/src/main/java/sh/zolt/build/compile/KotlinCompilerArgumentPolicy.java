package sh.zolt.build.compile;

import java.util.ArrayList;
import java.util.List;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.project.CompilerSettings;

/** Parses the bounded manifest compiler-argument grammar for one Kotlin source set. */
final class KotlinCompilerArgumentPolicy {
    private KotlinCompilerArgumentPolicy() {
    }

    static MappedArguments map(
            CompilerSettings compiler,
            KotlinCompilationScope scope) {
        List<String> arguments = scope == KotlinCompilationScope.MAIN
                ? compiler.args()
                : compiler.testArgs();
        boolean javaParameters = false;
        boolean warningsAsErrors = false;
        boolean progressiveMode = false;
        String languageVersion = "";
        String apiVersion = "";
        String jvmDefaultMode = "";
        List<String> optIns = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            switch (argument) {
                case "-parameters" -> {
                    if (javaParameters) {
                        throw duplicateArgument(scope, argument);
                    }
                    javaParameters = true;
                }
                case "-Werror" -> {
                    if (warningsAsErrors) {
                        throw duplicateArgument(scope, argument);
                    }
                    warningsAsErrors = true;
                }
                case "-progressive" -> {
                    if (progressiveMode) {
                        throw duplicateArgument(scope, argument);
                    }
                    progressiveMode = true;
                }
                case "-language-version" -> {
                    if (!languageVersion.isEmpty()) {
                        throw duplicateArgument(scope, argument);
                    }
                    languageVersion = versionArgument(arguments, ++index, scope, argument);
                }
                case "-api-version" -> {
                    if (!apiVersion.isEmpty()) {
                        throw duplicateArgument(scope, argument);
                    }
                    apiVersion = versionArgument(arguments, ++index, scope, argument);
                }
                default -> {
                    if (argument.startsWith("-jvm-default=")) {
                        if (!jvmDefaultMode.isEmpty()) {
                            throw duplicateArgument(scope, argument);
                        }
                        jvmDefaultMode = jvmDefaultArgument(scope, argument);
                    } else if (argument.startsWith("-opt-in=")) {
                        String optIn = optInArgument(scope, argument);
                        if (optIns.contains(optIn)) {
                            throw duplicateArgument(scope, argument);
                        }
                        optIns.add(optIn);
                    } else {
                        throw unsupportedArgument(scope, argument);
                    }
                }
            }
        }
        return new MappedArguments(
                javaParameters,
                warningsAsErrors,
                progressiveMode,
                languageVersion,
                apiVersion,
                jvmDefaultMode,
                List.copyOf(optIns));
    }

    private static String jvmDefaultArgument(
            KotlinCompilationScope scope,
            String argument) {
        String value = argument.substring("-jvm-default=".length());
        if (!List.of("enable", "no-compatibility", "disable").contains(value)) {
            throw unsupported(
                    scope,
                    argumentsPath(scope)
                            + " contains invalid Kotlin JVM-default argument `" + argument + "`",
                    "Use `-jvm-default=enable`, `-jvm-default=no-compatibility`, or"
                            + " `-jvm-default=disable`, or remove the argument.");
        }
        return value;
    }

    private static String optInArgument(
            KotlinCompilationScope scope,
            String argument) {
        String value = argument.substring("-opt-in=".length());
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            throw unsupported(
                    scope,
                    argumentsPath(scope)
                            + " contains invalid Kotlin opt-in argument `" + argument + "`",
                    "Use `-opt-in=<qualified.annotation.Name>` or remove the argument.");
        }
        return value;
    }

    private static String versionArgument(
            List<String> arguments,
            int index,
            KotlinCompilationScope scope,
            String option) {
        if (index >= arguments.size()) {
            throw invalidVersionArgument(scope, option, "<missing>");
        }
        String value = arguments.get(index);
        if (!value.matches("[1-9][0-9]*\\.[0-9]+")) {
            throw invalidVersionArgument(scope, option, value);
        }
        return value;
    }

    private static KotlinCompileException invalidVersionArgument(
            KotlinCompilationScope scope,
            String option,
            String value) {
        return unsupported(
                scope,
                argumentsPath(scope)
                        + " contains invalid Kotlin argument `" + option + " " + value + "`",
                "Use `" + option + " <major.minor>` with a version supported by the selected"
                        + " Kotlin compiler, or remove the pair.");
    }

    private static KotlinCompileException duplicateArgument(
            KotlinCompilationScope scope,
            String argument) {
        return unsupported(
                scope,
                argumentsPath(scope) + " contains duplicate compiler argument `" + argument + "`",
                "Use each exact compiler argument at most once, or keep this source set Java-only.");
    }

    private static KotlinCompileException unsupportedArgument(
            KotlinCompilationScope scope,
            String argument) {
        return unsupported(
                scope,
                argumentsPath(scope) + " contains unsupported compiler argument `" + argument + "`",
                "Use only a duplicate-free subset of `-parameters`, `-Werror`,"
                        + " `-progressive`, `-language-version <major.minor>`,"
                        + " `-api-version <major.minor>`, and one `-jvm-default=<mode>`, plus repeatable"
                        + " `-opt-in=<qualified.annotation.Name>` arguments; otherwise keep this source"
                        + " set Java-only.");
    }

    private static String argumentsPath(KotlinCompilationScope scope) {
        return scope == KotlinCompilationScope.MAIN
                ? "[compiler].args"
                : "[compiler.test].args";
    }

    private static KotlinCompileException unsupported(
            KotlinCompilationScope scope,
            String reason,
            String remediation) {
        return new KotlinCompileException(
                "Kotlin " + scope.label() + " compilation is not supported when " + reason + ". "
                        + remediation);
    }

    record MappedArguments(
            boolean javaParameters,
            boolean warningsAsErrors,
            boolean progressiveMode,
            String languageVersion,
            String apiVersion,
            String jvmDefaultMode,
            List<String> optIns) {
    }
}
