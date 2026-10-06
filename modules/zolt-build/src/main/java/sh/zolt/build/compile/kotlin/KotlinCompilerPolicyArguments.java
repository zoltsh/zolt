package sh.zolt.build.compile.kotlin;

import java.util.List;

/** Emits the deterministic compiler arguments represented by Kotlin policy. */
final class KotlinCompilerPolicyArguments {
    private KotlinCompilerPolicyArguments() {
    }

    static void addTo(List<String> arguments, KotlinCompilerPolicy policy) {
        KotlinCompilerPolicy.Language language = policy.language();
        KotlinCompilerPolicy.Diagnostics diagnostics = policy.diagnostics();
        KotlinCompilerPolicy.JvmInterop jvmInterop = policy.jvmInterop();
        KotlinCompilerPolicy.CodeGeneration codeGeneration = policy.codeGeneration();
        KotlinCompilerPolicy.Metadata metadata = policy.metadata();
        KotlinCompilerPolicy.AnnotationProcessing annotationProcessing =
                policy.annotationProcessing();

        addFlag(arguments, jvmInterop.javaParameters(), "-java-parameters");
        addDiagnostics(arguments, diagnostics);
        addLanguageSyntaxFlags(arguments, language);
        addFlag(arguments, jvmInterop.annotationTargetAll(), "-Xannotation-target-all");
        addFlag(arguments, jvmInterop.jvmExposeBoxed(), "-Xjvm-expose-boxed");
        addFlag(
                arguments,
                language.consistentDataClassCopyVisibility(),
                "-Xconsistent-data-class-copy-visibility");
        addFlag(arguments, jvmInterop.emitJvmTypeAnnotations(), "-Xemit-jvm-type-annotations");
        addFlag(
                arguments,
                jvmInterop.noNewJavaAnnotationTargets(),
                "-Xno-new-java-annotation-targets");
        addCodeGenerationFlags(arguments, codeGeneration);
        addMetadataFlags(arguments, metadata);
        addFlag(arguments, language.allowKotlinPackage(), "-Xallow-kotlin-package");
        if (annotationProcessing.useK2Kapt()) {
            arguments.add("-Xuse-k2-kapt");
        }
        addFlag(arguments, jvmInterop.jvmPreview(), "-Xjvm-enable-preview");
        addFlag(
                arguments,
                metadata.allowUnstableDependencies(),
                "-Xallow-unstable-dependencies");
        addFlag(arguments, codeGeneration.noParamAssertions(), "-Xno-param-assertions");
        addFlag(arguments, codeGeneration.noCallAssertions(), "-Xno-call-assertions");
        addFlag(arguments, codeGeneration.noReceiverAssertions(), "-Xno-receiver-assertions");
        addValues(arguments, policy);
    }

    private static void addDiagnostics(
            List<String> arguments,
            KotlinCompilerPolicy.Diagnostics diagnostics) {
        if (diagnostics.suppressWarnings()) {
            arguments.add("-nowarn");
        }
        if (diagnostics.warningsAsErrors()) {
            arguments.add("-Werror");
        }
        if (diagnostics.extraWarnings()) {
            arguments.add("-Wextra");
        }
        if (diagnostics.reportAllWarnings()) {
            arguments.add("-Xreport-all-warnings");
        }
        if (diagnostics.renderInternalDiagnosticNames()) {
            arguments.add("-Xrender-internal-diagnostic-names");
        }
    }

    private static void addLanguageSyntaxFlags(
            List<String> arguments,
            KotlinCompilerPolicy.Language language) {
        if (language.progressiveMode()) {
            arguments.add("-progressive");
        }
        if (language.contextSensitiveResolution()) {
            arguments.add("-Xcontext-sensitive-resolution");
        }
        if (language.contextReceivers()) {
            arguments.add("-Xcontext-receivers");
        }
        if (language.contextParameters()) {
            arguments.add("-Xcontext-parameters");
        }
        if (language.whenGuards()) {
            arguments.add("-Xwhen-guards");
        }
        if (language.multiDollarInterpolation()) {
            arguments.add("-Xmulti-dollar-interpolation");
        }
        if (language.nonLocalBreakContinue()) {
            arguments.add("-Xnon-local-break-continue");
        }
        if (language.nestedTypeAliases()) {
            arguments.add("-Xnested-type-aliases");
        }
    }

    private static void addCodeGenerationFlags(
            List<String> arguments,
            KotlinCompilerPolicy.CodeGeneration codeGeneration) {
        addFlag(arguments, codeGeneration.noSourceDebugExtension(), "-Xno-source-debug-extension");
        addFlag(arguments, codeGeneration.noUnifiedNullChecks(), "-Xno-unified-null-checks");
        addFlag(arguments, codeGeneration.noOptimize(), "-Xno-optimize");
        addFlag(arguments, codeGeneration.noInline(), "-Xno-inline");
        addFlag(arguments, codeGeneration.useInlineScopesNumbers(), "-Xuse-inline-scopes-numbers");
        addFlag(
                arguments,
                codeGeneration.use14InlineClassesManglingScheme(),
                "-Xuse-14-inline-classes-mangling-scheme");
        addFlag(
                arguments,
                codeGeneration.enhancedCoroutinesDebugging(),
                "-Xenhanced-coroutines-debugging");
        addFlag(arguments, codeGeneration.sanitizeParentheses(), "-Xsanitize-parentheses");
        addFlag(arguments, codeGeneration.multifilePartsInherit(), "-Xmultifile-parts-inherit");
        addFlag(arguments, codeGeneration.validateBytecode(), "-Xvalidate-bytecode");
        addFlag(
                arguments,
                codeGeneration.indyAllowAnnotatedLambdas(),
                "-Xindy-allow-annotated-lambdas");
    }

    private static void addMetadataFlags(
            List<String> arguments,
            KotlinCompilerPolicy.Metadata metadata) {
        addFlag(
                arguments,
                metadata.generateStrictMetadataVersion(),
                "-Xgenerate-strict-metadata-version");
        addFlag(arguments, metadata.annotationsInMetadata(), "-Xannotations-in-metadata");
        addFlag(arguments, metadata.useTypeTable(), "-Xuse-type-table");
        addFlag(arguments, metadata.useOldClassFilesReading(), "-Xuse-old-class-files-reading");
        addFlag(
                arguments,
                metadata.skipMetadataVersionCheck(),
                "-Xskip-metadata-version-check");
        addFlag(arguments, metadata.skipPrereleaseCheck(), "-Xskip-prerelease-check");
    }

    private static void addValues(
            List<String> arguments,
            KotlinCompilerPolicy policy) {
        KotlinCompilerPolicy.Language language = policy.language();
        KotlinCompilerPolicy.Diagnostics diagnostics = policy.diagnostics();
        KotlinCompilerPolicy.JvmInterop jvmInterop = policy.jvmInterop();
        KotlinCompilerPolicy.CodeGeneration codeGeneration = policy.codeGeneration();
        KotlinCompilerPolicy.Metadata metadata = policy.metadata();

        addValue(arguments, "-Xbackend-threads=", codeGeneration.backendThreads());
        addMode(arguments, "-Xabi-stability=", metadata.abiStabilityMode());
        addMode(
                arguments,
                "-Xannotation-default-target=",
                language.annotationDefaultTargetMode());
        addMode(arguments, "-Xassertions=", codeGeneration.assertionMode());
        addMode(arguments, "-Xreturn-value-checker=", language.returnValueCheckerMode());
        addMode(arguments, "-Xjspecify-annotations=", jvmInterop.jspecifyAnnotationsMode());
        addMode(arguments, "-Xjsr305=", jvmInterop.jsr305Mode());
        addMode(
                arguments,
                "-Xsupport-compatqual-checker-framework-annotations=",
                jvmInterop.compatqualAnnotationsMode());
        jvmInterop.nullabilityAnnotations().forEach(value ->
                arguments.add("-Xnullability-annotations=" + value));
        addMode(arguments, "-Xstring-concat=", codeGeneration.stringConcatMode());
        addMode(arguments, "-Xlambdas=", codeGeneration.lambdaMode());
        addMode(arguments, "-Xsam-conversions=", codeGeneration.samConversionMode());
        addPair(arguments, "-language-version", language.languageVersion());
        addPair(arguments, "-api-version", language.apiVersion());
        addMode(arguments, "-jvm-default=", jvmInterop.jvmDefaultMode());
        addMode(arguments, "-Xexplicit-api=", language.explicitApiMode());
        diagnostics.warningLevels().forEach(level -> arguments.add("-Xwarning-level=" + level));
        language.optIns().forEach(optIn -> arguments.add("-opt-in=" + optIn));
    }

    private static void addFlag(List<String> arguments, boolean enabled, String argument) {
        if (enabled) {
            arguments.add(argument);
        }
    }

    private static void addValue(List<String> arguments, String prefix, String value) {
        if (!value.isEmpty()) {
            arguments.add(prefix + value);
        }
    }

    private static void addMode(
            List<String> arguments,
            String prefix,
            KotlinCompilerModes.Value mode) {
        if (mode.configured()) {
            arguments.add(prefix + mode.argumentValue());
        }
    }

    private static void addPair(List<String> arguments, String name, String value) {
        if (!value.isEmpty()) {
            arguments.add(name);
            arguments.add(value);
        }
    }
}
