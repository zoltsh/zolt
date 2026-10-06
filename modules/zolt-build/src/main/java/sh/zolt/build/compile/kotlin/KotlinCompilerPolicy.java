package sh.zolt.build.compile.kotlin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.AbiStabilityMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.AnnotationDefaultTargetMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.AssertionMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.ClosureGenerationMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.CompatqualAnnotationsMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.ExplicitApiMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.JvmDefaultMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.NullabilityMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.ReturnValueCheckerMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.StringConcatMode;
import sh.zolt.build.compile.kotlin.kapt.KotlinAnnotationProcessorOptions;

/** Immutable Kotlin compiler policy grouped by the responsibility of each option. */
public record KotlinCompilerPolicy(
        Language language,
        Diagnostics diagnostics,
        JvmInterop jvmInterop,
        CodeGeneration codeGeneration,
        Metadata metadata,
        AnnotationProcessing annotationProcessing) {
    public KotlinCompilerPolicy {
        language = Objects.requireNonNull(language, "Kotlin language policy is required.");
        diagnostics = Objects.requireNonNull(diagnostics, "Kotlin diagnostics policy is required.");
        jvmInterop = Objects.requireNonNull(jvmInterop, "Kotlin JVM interop policy is required.");
        codeGeneration = Objects.requireNonNull(
                codeGeneration,
                "Kotlin code-generation policy is required.");
        metadata = Objects.requireNonNull(metadata, "Kotlin metadata policy is required.");
        annotationProcessing = Objects.requireNonNull(
                annotationProcessing,
                "Kotlin annotation-processing policy is required.");
    }

    /** Kotlin language and source-compatibility behavior. */
    public record Language(
            boolean progressiveMode,
            boolean contextSensitiveResolution,
            boolean contextReceivers,
            boolean contextParameters,
            boolean whenGuards,
            boolean multiDollarInterpolation,
            boolean nonLocalBreakContinue,
            boolean nestedTypeAliases,
            boolean consistentDataClassCopyVisibility,
            boolean allowKotlinPackage,
            AnnotationDefaultTargetMode annotationDefaultTargetMode,
            ReturnValueCheckerMode returnValueCheckerMode,
            String languageVersion,
            String apiVersion,
            ExplicitApiMode explicitApiMode,
            List<String> optIns) {
        public Language {
            annotationDefaultTargetMode = Objects.requireNonNull(
                    annotationDefaultTargetMode,
                    "Kotlin annotation-default-target mode is required.");
            returnValueCheckerMode = Objects.requireNonNull(
                    returnValueCheckerMode,
                    "Kotlin return-value-checker mode is required.");
            languageVersion = optional(languageVersion);
            apiVersion = optional(apiVersion);
            explicitApiMode = Objects.requireNonNull(
                    explicitApiMode,
                    "Kotlin explicit-API mode is required.");
            optIns = copy(optIns, "compiler opt-in annotation");
        }
    }

    /** Warning and compiler-diagnostic behavior. */
    public record Diagnostics(
            boolean warningsAsErrors,
            boolean suppressWarnings,
            boolean extraWarnings,
            boolean reportAllWarnings,
            boolean renderInternalDiagnosticNames,
            List<String> warningLevels) {
        public Diagnostics {
            warningLevels = copy(warningLevels, "compiler warning level");
        }
    }

    /** JVM language interop and bytecode-target semantics. */
    public record JvmInterop(
            boolean javaParameters,
            boolean annotationTargetAll,
            boolean jvmExposeBoxed,
            boolean emitJvmTypeAnnotations,
            boolean noNewJavaAnnotationTargets,
            boolean jvmPreview,
            NullabilityMode jspecifyAnnotationsMode,
            NullabilityMode jsr305Mode,
            CompatqualAnnotationsMode compatqualAnnotationsMode,
            JvmDefaultMode jvmDefaultMode,
            List<String> nullabilityAnnotations) {
        public JvmInterop {
            jspecifyAnnotationsMode = Objects.requireNonNull(
                    jspecifyAnnotationsMode,
                    "Kotlin JSpecify-annotation mode is required.");
            jsr305Mode = Objects.requireNonNull(
                    jsr305Mode,
                    "Kotlin JSR-305 mode is required.");
            compatqualAnnotationsMode = Objects.requireNonNull(
                    compatqualAnnotationsMode,
                    "Kotlin compatqual-annotation mode is required.");
            jvmDefaultMode = Objects.requireNonNull(
                    jvmDefaultMode,
                    "Kotlin JVM-default mode is required.");
            nullabilityAnnotations = copy(
                    nullabilityAnnotations,
                    "compiler nullability-annotation rule");
        }
    }

    /** JVM backend and emitted-code behavior. */
    public record CodeGeneration(
            boolean noSourceDebugExtension,
            boolean noUnifiedNullChecks,
            boolean noOptimize,
            boolean noInline,
            boolean useInlineScopesNumbers,
            boolean use14InlineClassesManglingScheme,
            boolean enhancedCoroutinesDebugging,
            boolean sanitizeParentheses,
            boolean multifilePartsInherit,
            boolean validateBytecode,
            boolean indyAllowAnnotatedLambdas,
            boolean noParamAssertions,
            boolean noCallAssertions,
            boolean noReceiverAssertions,
            String backendThreads,
            AssertionMode assertionMode,
            StringConcatMode stringConcatMode,
            ClosureGenerationMode lambdaMode,
            ClosureGenerationMode samConversionMode) {
        public CodeGeneration {
            backendThreads = optional(backendThreads);
            assertionMode = Objects.requireNonNull(
                    assertionMode,
                    "Kotlin assertion mode is required.");
            stringConcatMode = Objects.requireNonNull(
                    stringConcatMode,
                    "Kotlin string-concatenation mode is required.");
            lambdaMode = Objects.requireNonNull(
                    lambdaMode,
                    "Kotlin lambda-generation mode is required.");
            samConversionMode = Objects.requireNonNull(
                    samConversionMode,
                    "Kotlin SAM-conversion mode is required.");
        }
    }

    /** Kotlin metadata and dependency-compatibility behavior. */
    public record Metadata(
            boolean generateStrictMetadataVersion,
            boolean annotationsInMetadata,
            boolean useTypeTable,
            boolean useOldClassFilesReading,
            boolean skipMetadataVersionCheck,
            boolean skipPrereleaseCheck,
            boolean allowUnstableDependencies,
            AbiStabilityMode abiStabilityMode) {
        public Metadata {
            abiStabilityMode = Objects.requireNonNull(
                    abiStabilityMode,
                    "Kotlin ABI-stability mode is required.");
        }
    }

    /** Bounded Kotlin annotation-processing configuration. */
    public record AnnotationProcessing(
            boolean useK2Kapt,
            Map<String, String> processorOptions) {
        public AnnotationProcessing {
            processorOptions = new KotlinAnnotationProcessorOptions(processorOptions).values();
        }
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? "" : value.strip();
    }

    private static List<String> copy(List<String> values, String label) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .map(value -> require(value, label))
                .toList();
    }

    private static String require(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new KotlinCompileException("Kotlin compilation requires a " + label + ".");
        }
        return value.strip();
    }
}
