package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.kotlin.kapt.KotlinAnnotationProcessorOptions;

/** Immutable, normalized options for one Kotlin/JVM compiler invocation. */
public record KotlinCompilerOptions(
        String release,
        String moduleName,
        boolean hostPlatformApi,
        boolean useJdkRelease,
        boolean javaParameters,
        boolean warningsAsErrors,
        boolean suppressWarnings,
        boolean extraWarnings,
        boolean reportAllWarnings,
        boolean renderInternalDiagnosticNames,
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
        boolean noInline,
        boolean useInlineScopesNumbers,
        boolean use14InlineClassesManglingScheme,
        boolean enhancedCoroutinesDebugging,
        boolean sanitizeParentheses,
        boolean multifilePartsInherit,
        boolean validateBytecode,
        boolean indyAllowAnnotatedLambdas,
        boolean generateStrictMetadataVersion,
        boolean annotationsInMetadata,
        boolean useTypeTable,
        boolean useOldClassFilesReading,
        boolean skipMetadataVersionCheck,
        boolean jvmPreview,
        boolean allowUnstableDependencies,
        boolean noParamAssertions,
        boolean noCallAssertions,
        boolean noReceiverAssertions,
        String backendThreads,
        String abiStabilityMode,
        String annotationDefaultTargetMode,
        String assertionMode,
        String returnValueCheckerMode,
        String jspecifyAnnotationsMode,
        String jsr305Mode,
        String compatqualAnnotationsMode,
        String stringConcatMode,
        String lambdaMode,
        String samConversionMode,
        String languageVersion,
        String apiVersion,
        String jvmDefaultMode,
        String explicitApiMode,
        List<String> nullabilityAnnotations,
        List<String> warningLevels,
        List<String> optIns,
        Map<String, String> annotationProcessorOptions,
        Path friendPath) {
    public KotlinCompilerOptions(String release, String moduleName, boolean hostPlatformApi) {
        this(release, moduleName, hostPlatformApi, !hostPlatformApi, false, false, null);
    }

    public KotlinCompilerOptions(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            Map<String, String> annotationProcessorOptions) {
        this(
                release,
                moduleName,
                hostPlatformApi,
                !hostPlatformApi,
                false,
                false,
                annotationProcessorOptions,
                null);
    }

    public KotlinCompilerOptions(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            boolean useJdkRelease) {
        this(release, moduleName, hostPlatformApi, useJdkRelease, false, false, null);
    }

    public KotlinCompilerOptions(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            boolean useJdkRelease,
            boolean javaParameters) {
        this(release, moduleName, hostPlatformApi, useJdkRelease, javaParameters, false, null);
    }

    public KotlinCompilerOptions(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            boolean useJdkRelease,
            boolean javaParameters,
            boolean warningsAsErrors) {
        this(
                release,
                moduleName,
                hostPlatformApi,
                useJdkRelease,
                javaParameters,
                warningsAsErrors,
                null);
    }

    public KotlinCompilerOptions(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            boolean useJdkRelease,
            Path friendPath) {
        this(release, moduleName, hostPlatformApi, useJdkRelease, false, false, friendPath);
    }

    /** Compatibility constructor for callers that predate mapped Kotlin warning policy. */
    public KotlinCompilerOptions(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            boolean useJdkRelease,
            boolean javaParameters,
            Path friendPath) {
        this(release, moduleName, hostPlatformApi, useJdkRelease, javaParameters, false, friendPath);
    }

    /** Compatibility constructor for callers that predate Kotlin language/API pinning. */
    public KotlinCompilerOptions(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            boolean useJdkRelease,
            boolean javaParameters,
            boolean warningsAsErrors,
            Path friendPath) {
        this(
                release,
                moduleName,
                hostPlatformApi,
                useJdkRelease,
                javaParameters,
                warningsAsErrors,
                Map.of(),
                friendPath);
    }

    private KotlinCompilerOptions(
            String release,
            String moduleName,
            boolean hostPlatformApi,
            boolean useJdkRelease,
            boolean javaParameters,
            boolean warningsAsErrors,
            Map<String, String> annotationProcessorOptions,
            Path friendPath) {
        this(
                release,
                moduleName,
                hostPlatformApi,
                useJdkRelease,
                javaParameters,
                warningsAsErrors,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                annotationProcessorOptions,
                friendPath);
    }

    public KotlinCompilerOptions {
        release = KotlinCompilerOptionValues.require(release, "effective Java release");
        moduleName = KotlinCompilerOptionValues.require(moduleName, "module name");
        backendThreads = KotlinCompilerOptionValues.optional(backendThreads);
        abiStabilityMode = KotlinCompilerOptionValues.optional(abiStabilityMode);
        annotationDefaultTargetMode = KotlinCompilerOptionValues.optional(
                annotationDefaultTargetMode);
        assertionMode = KotlinCompilerOptionValues.optional(assertionMode);
        returnValueCheckerMode = KotlinCompilerOptionValues.optional(returnValueCheckerMode);
        jspecifyAnnotationsMode = KotlinCompilerOptionValues.optional(
                jspecifyAnnotationsMode);
        jsr305Mode = KotlinCompilerOptionValues.optional(jsr305Mode);
        compatqualAnnotationsMode = KotlinCompilerOptionValues.optional(
                compatqualAnnotationsMode);
        stringConcatMode = KotlinCompilerOptionValues.optional(stringConcatMode);
        lambdaMode = KotlinCompilerOptionValues.optional(lambdaMode);
        samConversionMode = KotlinCompilerOptionValues.optional(samConversionMode);
        languageVersion = KotlinCompilerOptionValues.optional(languageVersion);
        apiVersion = KotlinCompilerOptionValues.optional(apiVersion);
        jvmDefaultMode = KotlinCompilerOptionValues.optional(jvmDefaultMode);
        explicitApiMode = KotlinCompilerOptionValues.optional(explicitApiMode);
        nullabilityAnnotations = KotlinCompilerOptionValues.copy(
                nullabilityAnnotations,
                "compiler nullability-annotation rule");
        warningLevels = KotlinCompilerOptionValues.copy(
                warningLevels,
                "compiler warning level");
        optIns = KotlinCompilerOptionValues.copy(optIns, "compiler opt-in annotation");
        annotationProcessorOptions = new KotlinAnnotationProcessorOptions(
                annotationProcessorOptions).values();
        friendPath = friendPath == null ? null : friendPath.normalize();
        if (friendPath != null && friendPath.toString().contains(",")) {
            throw new KotlinCompileException(
                    "Kotlin test compilation cannot use a friend output path containing a comma because "
                            + "kotlinc treats commas as friend-path separators. Move the project to a path "
                            + "without commas and try again.");
        }
        if (hostPlatformApi && useJdkRelease) {
            throw new KotlinCompileException(
                    "Kotlin host platform-API mode cannot use -Xjdk-release.");
        }
    }

    public KotlinCompilerOptions withFriendPath(Path path) {
        return KotlinCompilerOptionsCopy.withFriendPath(this, path);
    }
}
