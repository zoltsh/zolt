package sh.zolt.build.compile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        Set<String> standaloneArguments = new HashSet<>();
        String annotationDefaultTargetMode = "";
        String assertionMode = "";
        String jspecifyAnnotationsMode = "";
        String jsr305Mode = "";
        String stringConcatMode = "";
        String lambdaMode = "";
        String samConversionMode = "";
        String languageVersion = "";
        String apiVersion = "";
        String jvmDefaultMode = "";
        String explicitApiMode = "";
        List<String> nullabilityAnnotations = new ArrayList<>();
        List<String> warningLevels = new ArrayList<>();
        List<String> optIns = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            switch (argument) {
                case "-parameters",
                        "-Werror",
                        "-nowarn",
                        "-Wextra",
                        "-progressive",
                        "-Xcontext-sensitive-resolution",
                        "-Xcontext-parameters",
                        "-Xwhen-guards",
                        "-Xmulti-dollar-interpolation",
                        "-Xnon-local-break-continue",
                        "-Xnested-type-aliases",
                        "-Xannotation-target-all",
                        "-Xjvm-expose-boxed",
                        "-Xconsistent-data-class-copy-visibility",
                        "-Xemit-jvm-type-annotations",
                        "-Xno-new-java-annotation-targets",
                        "-Xno-source-debug-extension",
                        "-Xno-unified-null-checks",
                        "-Xno-optimize",
                        "-Xvalidate-bytecode",
                        "-Xindy-allow-annotated-lambdas",
                        "-Xgenerate-strict-metadata-version",
                        "-Xannotations-in-metadata" -> {
                    if (!standaloneArguments.add(argument)) {
                        throw duplicateArgument(scope, argument);
                    }
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
                    } else if (argument.startsWith("-Xassertions=")) {
                        if (!assertionMode.isEmpty()) {
                            throw duplicateArgument(scope, argument);
                        }
                        assertionMode = KotlinCompilerModeArguments.assertions(scope, argument);
                    } else if (argument.startsWith("-Xjspecify-annotations=")) {
                        if (!jspecifyAnnotationsMode.isEmpty()) {
                            throw duplicateArgument(scope, argument);
                        }
                        jspecifyAnnotationsMode =
                                KotlinCompilerModeArguments.jspecifyAnnotations(scope, argument);
                    } else if (argument.startsWith("-Xjsr305=")) {
                        if (!jsr305Mode.isEmpty()) {
                            throw duplicateArgument(scope, argument);
                        }
                        jsr305Mode = KotlinCompilerModeArguments.jsr305(scope, argument);
                    } else if (argument.startsWith("-Xnullability-annotations=")) {
                        String nullabilityAnnotation =
                                KotlinCompilerRepeatableArgumentPolicy.nullabilityAnnotation(
                                        scope,
                                        argument);
                        String annotationPackage =
                                KotlinCompilerRepeatableArgumentPolicy.nullabilityAnnotationPackage(
                                        nullabilityAnnotation);
                        if (nullabilityAnnotations.stream()
                                .map(value -> KotlinCompilerRepeatableArgumentPolicy
                                        .nullabilityAnnotationPackage(value))
                                .anyMatch(annotationPackage::equals)) {
                            throw duplicateArgument(scope, argument);
                        }
                        nullabilityAnnotations.add(nullabilityAnnotation);
                    } else if (argument.startsWith("-Xwarning-level=")) {
                        String warningLevel =
                                KotlinCompilerRepeatableArgumentPolicy.warningLevel(scope, argument);
                        String diagnostic = KotlinCompilerRepeatableArgumentPolicy.warningDiagnostic(
                                warningLevel);
                        if (warningLevels.stream()
                                .map(KotlinCompilerRepeatableArgumentPolicy::warningDiagnostic)
                                .anyMatch(diagnostic::equals)) {
                            throw duplicateArgument(scope, argument);
                        }
                        warningLevels.add(warningLevel);
                    } else if (argument.startsWith("-opt-in=")) {
                        String optIn = KotlinCompilerRepeatableArgumentPolicy.optIn(scope, argument);
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
        if (standaloneArguments.contains("-nowarn") && standaloneArguments.contains("-Werror")) {
            throw incompatibleArguments(scope, "-nowarn", "-Werror");
        }
        if (standaloneArguments.contains("-nowarn") && standaloneArguments.contains("-Wextra")) {
            throw incompatibleArguments(scope, "-nowarn", "-Wextra");
        }
        if (standaloneArguments.contains("-Xindy-allow-annotated-lambdas")
                && !"indy".equals(lambdaMode)) {
            throw unsupported(
                    scope,
                    argumentsPath(scope)
                            + " uses `-Xindy-allow-annotated-lambdas` without `-Xlambdas=indy`",
                    "Add `-Xlambdas=indy`, or remove the annotated-lambda compatibility flag.");
        }
        return new MappedArguments(
                standaloneArguments.contains("-parameters"),
                standaloneArguments.contains("-Werror"),
                standaloneArguments.contains("-nowarn"),
                standaloneArguments.contains("-Wextra"),
                standaloneArguments.contains("-progressive"),
                standaloneArguments.contains("-Xcontext-sensitive-resolution"),
                standaloneArguments.contains("-Xcontext-parameters"),
                standaloneArguments.contains("-Xwhen-guards"),
                standaloneArguments.contains("-Xmulti-dollar-interpolation"),
                standaloneArguments.contains("-Xnon-local-break-continue"),
                standaloneArguments.contains("-Xnested-type-aliases"),
                standaloneArguments.contains("-Xannotation-target-all"),
                standaloneArguments.contains("-Xjvm-expose-boxed"),
                standaloneArguments.contains("-Xconsistent-data-class-copy-visibility"),
                standaloneArguments.contains("-Xemit-jvm-type-annotations"),
                standaloneArguments.contains("-Xno-new-java-annotation-targets"),
                standaloneArguments.contains("-Xno-source-debug-extension"),
                standaloneArguments.contains("-Xno-unified-null-checks"),
                standaloneArguments.contains("-Xno-optimize"),
                standaloneArguments.contains("-Xvalidate-bytecode"),
                standaloneArguments.contains("-Xindy-allow-annotated-lambdas"),
                standaloneArguments.contains("-Xgenerate-strict-metadata-version"),
                standaloneArguments.contains("-Xannotations-in-metadata"),
                annotationDefaultTargetMode,
                assertionMode,
                jspecifyAnnotationsMode,
                jsr305Mode,
                stringConcatMode,
                lambdaMode,
                samConversionMode,
                languageVersion,
                apiVersion,
                jvmDefaultMode,
                explicitApiMode,
                List.copyOf(nullabilityAnnotations),
                List.copyOf(warningLevels),
                List.copyOf(optIns));
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
            boolean multiDollarInterpolation,
            boolean nonLocalBreakContinue,
            boolean nestedTypeAliases,
            boolean annotationTargetAll,
            boolean jvmExposeBoxed,
            boolean consistentDataClassCopyVisibility,
            boolean emitJvmTypeAnnotations,
            boolean noNewJavaAnnotationTargets,
            boolean noSourceDebugExtension,
            boolean noUnifiedNullChecks,
            boolean noOptimize,
            boolean validateBytecode,
            boolean indyAllowAnnotatedLambdas,
            boolean generateStrictMetadataVersion,
            boolean annotationsInMetadata,
            String annotationDefaultTargetMode,
            String assertionMode,
            String jspecifyAnnotationsMode,
            String jsr305Mode,
            String stringConcatMode,
            String lambdaMode,
            String samConversionMode,
            String languageVersion,
            String apiVersion,
            String jvmDefaultMode,
            String explicitApiMode,
            List<String> nullabilityAnnotations,
            List<String> warningLevels,
            List<String> optIns) {
    }
}
