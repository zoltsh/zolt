package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.Objects;

/** Copies immutable Kotlin compiler options while replacing invocation-local values. */
final class KotlinCompilerOptionsCopy {
    private KotlinCompilerOptionsCopy() {
    }

    static KotlinCompilerOptions withFriendPath(
            KotlinCompilerOptions options,
            Path path) {
        return new KotlinCompilerOptions(
                options.release(),
                options.moduleName(),
                options.hostPlatformApi(),
                options.useJdkRelease(),
                options.javaParameters(),
                options.warningsAsErrors(),
                options.suppressWarnings(),
                options.extraWarnings(),
                options.reportAllWarnings(),
                options.renderInternalDiagnosticNames(),
                options.progressiveMode(),
                options.contextSensitiveResolution(),
                options.contextReceivers(),
                options.contextParameters(),
                options.whenGuards(),
                options.multiDollarInterpolation(),
                options.nonLocalBreakContinue(),
                options.nestedTypeAliases(),
                options.annotationTargetAll(),
                options.jvmExposeBoxed(),
                options.consistentDataClassCopyVisibility(),
                options.emitJvmTypeAnnotations(),
                options.noNewJavaAnnotationTargets(),
                options.noSourceDebugExtension(),
                options.noUnifiedNullChecks(),
                options.noOptimize(),
                options.noInline(),
                options.useInlineScopesNumbers(),
                options.use14InlineClassesManglingScheme(),
                options.enhancedCoroutinesDebugging(),
                options.sanitizeParentheses(),
                options.multifilePartsInherit(),
                options.validateBytecode(),
                options.indyAllowAnnotatedLambdas(),
                options.generateStrictMetadataVersion(),
                options.annotationsInMetadata(),
                options.useTypeTable(),
                options.useOldClassFilesReading(),
                options.skipMetadataVersionCheck(),
                options.skipPrereleaseCheck(),
                options.jvmPreview(),
                options.allowUnstableDependencies(),
                options.noParamAssertions(),
                options.noCallAssertions(),
                options.noReceiverAssertions(),
                options.backendThreads(),
                options.abiStabilityMode(),
                options.annotationDefaultTargetMode(),
                options.assertionMode(),
                options.returnValueCheckerMode(),
                options.jspecifyAnnotationsMode(),
                options.jsr305Mode(),
                options.compatqualAnnotationsMode(),
                options.stringConcatMode(),
                options.lambdaMode(),
                options.samConversionMode(),
                options.languageVersion(),
                options.apiVersion(),
                options.jvmDefaultMode(),
                options.explicitApiMode(),
                options.nullabilityAnnotations(),
                options.warningLevels(),
                options.optIns(),
                options.annotationProcessorOptions(),
                Objects.requireNonNull(path, "Kotlin friend path is required."));
    }
}
