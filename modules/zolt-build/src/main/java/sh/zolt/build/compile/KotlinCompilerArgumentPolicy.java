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
        boolean suppressWarnings = false;
        boolean extraWarnings = false;
        boolean progressiveMode = false;
        boolean contextSensitiveResolution = false;
        boolean contextParameters = false;
        boolean whenGuards = false;
        boolean nestedTypeAliases = false;
        boolean annotationTargetAll = false;
        boolean jvmExposeBoxed = false;
        boolean consistentDataClassCopyVisibility = false;
        String annotationDefaultTargetMode = "";
        String stringConcatMode = "";
        String lambdaMode = "";
        String samConversionMode = "";
        String languageVersion = "";
        String apiVersion = "";
        String jvmDefaultMode = "";
        String explicitApiMode = "";
        List<String> warningLevels = new ArrayList<>();
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
                case "-nowarn" -> {
                    if (suppressWarnings) {
                        throw duplicateArgument(scope, argument);
                    }
                    suppressWarnings = true;
                }
                case "-Wextra" -> {
                    if (extraWarnings) {
                        throw duplicateArgument(scope, argument);
                    }
                    extraWarnings = true;
                }
                case "-progressive" -> {
                    if (progressiveMode) {
                        throw duplicateArgument(scope, argument);
                    }
                    progressiveMode = true;
                }
                case "-Xcontext-sensitive-resolution" -> {
                    if (contextSensitiveResolution) {
                        throw duplicateArgument(scope, argument);
                    }
                    contextSensitiveResolution = true;
                }
                case "-Xcontext-parameters" -> {
                    if (contextParameters) {
                        throw duplicateArgument(scope, argument);
                    }
                    contextParameters = true;
                }
                case "-Xwhen-guards" -> {
                    if (whenGuards) {
                        throw duplicateArgument(scope, argument);
                    }
                    whenGuards = true;
                }
                case "-Xnested-type-aliases" -> {
                    if (nestedTypeAliases) {
                        throw duplicateArgument(scope, argument);
                    }
                    nestedTypeAliases = true;
                }
                case "-Xannotation-target-all" -> {
                    if (annotationTargetAll) {
                        throw duplicateArgument(scope, argument);
                    }
                    annotationTargetAll = true;
                }
                case "-Xjvm-expose-boxed" -> {
                    if (jvmExposeBoxed) {
                        throw duplicateArgument(scope, argument);
                    }
                    jvmExposeBoxed = true;
                }
                case "-Xconsistent-data-class-copy-visibility" -> {
                    if (consistentDataClassCopyVisibility) {
                        throw duplicateArgument(scope, argument);
                    }
                    consistentDataClassCopyVisibility = true;
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
                        jvmDefaultMode = KotlinCompilerModeArguments.jvmDefault(scope, argument);
                    } else if (argument.startsWith("-Xexplicit-api=")) {
                        if (!explicitApiMode.isEmpty()) {
                            throw duplicateArgument(scope, argument);
                        }
                        explicitApiMode = KotlinCompilerModeArguments.explicitApi(scope, argument);
                    } else if (argument.startsWith("-Xstring-concat=")) {
                        if (!stringConcatMode.isEmpty()) {
                            throw duplicateArgument(scope, argument);
                        }
                        stringConcatMode = KotlinCompilerModeArguments.stringConcat(scope, argument);
                    } else if (argument.startsWith("-Xlambdas=")) {
                        if (!lambdaMode.isEmpty()) {
                            throw duplicateArgument(scope, argument);
                        }
                        lambdaMode = KotlinCompilerModeArguments.lambda(scope, argument);
                    } else if (argument.startsWith("-Xsam-conversions=")) {
                        if (!samConversionMode.isEmpty()) {
                            throw duplicateArgument(scope, argument);
                        }
                        samConversionMode = KotlinCompilerModeArguments.samConversion(scope, argument);
                    } else if (argument.startsWith("-Xannotation-default-target=")) {
                        if (!annotationDefaultTargetMode.isEmpty()) {
                            throw duplicateArgument(scope, argument);
                        }
                        annotationDefaultTargetMode =
                                KotlinCompilerModeArguments.annotationDefaultTarget(scope, argument);
                    } else if (argument.startsWith("-Xwarning-level=")) {
                        String warningLevel = warningLevelArgument(scope, argument);
                        String diagnostic = warningDiagnostic(warningLevel);
                        if (warningLevels.stream()
                                .map(KotlinCompilerArgumentPolicy::warningDiagnostic)
                                .anyMatch(diagnostic::equals)) {
                            throw duplicateArgument(scope, argument);
                        }
                        warningLevels.add(warningLevel);
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
        if (suppressWarnings && warningsAsErrors) {
            throw incompatibleArguments(scope, "-nowarn", "-Werror");
        }
        if (suppressWarnings && extraWarnings) {
            throw incompatibleArguments(scope, "-nowarn", "-Wextra");
        }
        return new MappedArguments(
                javaParameters,
                warningsAsErrors,
                suppressWarnings,
                extraWarnings,
                progressiveMode,
                contextSensitiveResolution,
                contextParameters,
                whenGuards,
                nestedTypeAliases,
                annotationTargetAll,
                jvmExposeBoxed,
                consistentDataClassCopyVisibility,
                annotationDefaultTargetMode,
                stringConcatMode,
                lambdaMode,
                samConversionMode,
                languageVersion,
                apiVersion,
                jvmDefaultMode,
                explicitApiMode,
                List.copyOf(warningLevels),
                List.copyOf(optIns));
    }

    private static String warningLevelArgument(
            KotlinCompilationScope scope,
            String argument) {
        String value = argument.substring("-Xwarning-level=".length());
        if (!value.matches("[A-Z][A-Z0-9_]*:(error|warning|disabled)")) {
            throw unsupported(
                    scope,
                    argumentsPath(scope)
                            + " contains invalid Kotlin warning-level argument `" + argument + "`",
                    "Use `-Xwarning-level=DIAGNOSTIC_NAME:error`, `:warning`, or `:disabled`"
                            + " with an uppercase Kotlin diagnostic name, or remove the argument.");
        }
        return value;
    }

    private static String warningDiagnostic(String warningLevel) {
        return warningLevel.substring(0, warningLevel.indexOf(':'));
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
                "Configure each compiler option, opt-in, or diagnostic at most once, or keep this"
                        + " source set Java-only.");
    }

    private static KotlinCompileException incompatibleArguments(
            KotlinCompilationScope scope,
            String left,
            String right) {
        return unsupported(
                scope,
                argumentsPath(scope) + " combines incompatible compiler arguments `"
                        + left + "` and `" + right + "`",
                "Choose warning suppression or warning enforcement, but not both.");
    }

    private static KotlinCompileException unsupportedArgument(
            KotlinCompilationScope scope,
            String argument) {
        return unsupported(
                scope,
                argumentsPath(scope) + " contains unsupported compiler argument `" + argument + "`",
                KotlinCompilerArgumentGuidance.supportedArguments());
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
            boolean suppressWarnings,
            boolean extraWarnings,
            boolean progressiveMode,
            boolean contextSensitiveResolution,
            boolean contextParameters,
            boolean whenGuards,
            boolean nestedTypeAliases,
            boolean annotationTargetAll,
            boolean jvmExposeBoxed,
            boolean consistentDataClassCopyVisibility,
            String annotationDefaultTargetMode,
            String stringConcatMode,
            String lambdaMode,
            String samConversionMode,
            String languageVersion,
            String apiVersion,
            String jvmDefaultMode,
            String explicitApiMode,
            List<String> warningLevels,
            List<String> optIns) {
    }
}
