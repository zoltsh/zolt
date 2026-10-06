package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import sh.zolt.build.compile.kotlin.kapt.KotlinAnnotationProcessorOptions;
import sh.zolt.build.compile.kotlin.kapt.KotlinKaptOptions;
import sh.zolt.classpath.Classpath;

/** Builds the deterministic Kotlin compiler argument-file payload. */
final class KotlinCompilerInvocationArguments {
    private KotlinCompilerInvocationArguments() {
    }

    static List<String> build(
            Path jdkHome,
            List<Path> sources,
            Classpath compilationClasspath,
            Path outputDirectory,
            KotlinCompilerOptions options,
            String pathSeparator) {
        return build(
                jdkHome,
                sources,
                compilationClasspath,
                outputDirectory,
                options,
                null,
                pathSeparator);
    }

    static List<String> build(
            Path jdkHome,
            List<Path> sources,
            Classpath compilationClasspath,
            Path outputDirectory,
            KotlinCompilerOptions options,
            KotlinKaptOptions kaptOptions,
            String pathSeparator) {
        List<String> arguments = new ArrayList<>();
        arguments.add("-no-stdlib");
        arguments.add("-no-reflect");
        arguments.add("-jdk-home");
        arguments.add(jdkHome.toString());
        if (!options.useJdkRelease()) {
            arguments.add("-jvm-target");
            arguments.add("8".equals(options.release()) ? "1.8" : options.release());
        } else {
            arguments.add("-Xjdk-release=" + options.release());
        }
        if (options.javaParameters()) {
            arguments.add("-java-parameters");
        }
        if (options.suppressWarnings()) {
            arguments.add("-nowarn");
        }
        if (options.warningsAsErrors()) {
            arguments.add("-Werror");
        }
        if (options.extraWarnings()) {
            arguments.add("-Wextra");
        }
        if (options.reportAllWarnings()) {
            arguments.add("-Xreport-all-warnings");
        }
        if (options.renderInternalDiagnosticNames()) {
            arguments.add("-Xrender-internal-diagnostic-names");
        }
        if (options.progressiveMode()) {
            arguments.add("-progressive");
        }
        if (options.contextSensitiveResolution()) {
            arguments.add("-Xcontext-sensitive-resolution");
        }
        if (options.contextParameters()) {
            arguments.add("-Xcontext-parameters");
        }
        if (options.whenGuards()) {
            arguments.add("-Xwhen-guards");
        }
        if (options.multiDollarInterpolation()) {
            arguments.add("-Xmulti-dollar-interpolation");
        }
        if (options.nonLocalBreakContinue()) {
            arguments.add("-Xnon-local-break-continue");
        }
        if (options.nestedTypeAliases()) {
            arguments.add("-Xnested-type-aliases");
        }
        if (options.annotationTargetAll()) {
            arguments.add("-Xannotation-target-all");
        }
        if (options.jvmExposeBoxed()) {
            arguments.add("-Xjvm-expose-boxed");
        }
        if (options.consistentDataClassCopyVisibility()) {
            arguments.add("-Xconsistent-data-class-copy-visibility");
        }
        if (options.emitJvmTypeAnnotations()) {
            arguments.add("-Xemit-jvm-type-annotations");
        }
        if (options.noNewJavaAnnotationTargets()) {
            arguments.add("-Xno-new-java-annotation-targets");
        }
        if (options.noSourceDebugExtension()) {
            arguments.add("-Xno-source-debug-extension");
        }
        if (options.noUnifiedNullChecks()) {
            arguments.add("-Xno-unified-null-checks");
        }
        if (options.noOptimize()) {
            arguments.add("-Xno-optimize");
        }
        if (options.noInline()) {
            arguments.add("-Xno-inline");
        }
        if (options.useInlineScopesNumbers()) {
            arguments.add("-Xuse-inline-scopes-numbers");
        }
        if (options.use14InlineClassesManglingScheme()) {
            arguments.add("-Xuse-14-inline-classes-mangling-scheme");
        }
        if (options.enhancedCoroutinesDebugging()) {
            arguments.add("-Xenhanced-coroutines-debugging");
        }
        if (options.sanitizeParentheses()) {
            arguments.add("-Xsanitize-parentheses");
        }
        if (options.multifilePartsInherit()) {
            arguments.add("-Xmultifile-parts-inherit");
        }
        if (options.validateBytecode()) {
            arguments.add("-Xvalidate-bytecode");
        }
        if (options.indyAllowAnnotatedLambdas()) {
            arguments.add("-Xindy-allow-annotated-lambdas");
        }
        if (options.generateStrictMetadataVersion()) {
            arguments.add("-Xgenerate-strict-metadata-version");
        }
        if (options.annotationsInMetadata()) {
            arguments.add("-Xannotations-in-metadata");
        }
        if (options.useTypeTable()) {
            arguments.add("-Xuse-type-table");
        }
        if (options.jvmPreview()) {
            arguments.add("-Xjvm-enable-preview");
        }
        if (options.allowUnstableDependencies()) {
            arguments.add("-Xallow-unstable-dependencies");
        }
        if (options.noParamAssertions()) {
            arguments.add("-Xno-param-assertions");
        }
        if (options.noCallAssertions()) {
            arguments.add("-Xno-call-assertions");
        }
        if (options.noReceiverAssertions()) {
            arguments.add("-Xno-receiver-assertions");
        }
        if (!options.backendThreads().isEmpty()) {
            arguments.add("-Xbackend-threads=" + options.backendThreads());
        }
        if (!options.abiStabilityMode().isEmpty()) {
            arguments.add("-Xabi-stability=" + options.abiStabilityMode());
        }
        if (!options.annotationDefaultTargetMode().isEmpty()) {
            arguments.add("-Xannotation-default-target=" + options.annotationDefaultTargetMode());
        }
        if (!options.assertionMode().isEmpty()) {
            arguments.add("-Xassertions=" + options.assertionMode());
        }
        if (!options.returnValueCheckerMode().isEmpty()) {
            arguments.add("-Xreturn-value-checker=" + options.returnValueCheckerMode());
        }
        if (!options.jspecifyAnnotationsMode().isEmpty()) {
            arguments.add("-Xjspecify-annotations=" + options.jspecifyAnnotationsMode());
        }
        if (!options.jsr305Mode().isEmpty()) {
            arguments.add("-Xjsr305=" + options.jsr305Mode());
        }
        if (!options.compatqualAnnotationsMode().isEmpty()) {
            arguments.add("-Xsupport-compatqual-checker-framework-annotations="
                    + options.compatqualAnnotationsMode());
        }
        options.nullabilityAnnotations().forEach(value ->
                arguments.add("-Xnullability-annotations=" + value));
        if (!options.stringConcatMode().isEmpty()) {
            arguments.add("-Xstring-concat=" + options.stringConcatMode());
        }
        if (!options.lambdaMode().isEmpty()) {
            arguments.add("-Xlambdas=" + options.lambdaMode());
        }
        if (!options.samConversionMode().isEmpty()) {
            arguments.add("-Xsam-conversions=" + options.samConversionMode());
        }
        addVersion(arguments, "-language-version", options.languageVersion());
        addVersion(arguments, "-api-version", options.apiVersion());
        if (!options.jvmDefaultMode().isEmpty()) {
            arguments.add("-jvm-default=" + options.jvmDefaultMode());
        }
        if (!options.explicitApiMode().isEmpty()) {
            arguments.add("-Xexplicit-api=" + options.explicitApiMode());
        }
        options.warningLevels().forEach(level -> arguments.add("-Xwarning-level=" + level));
        options.optIns().forEach(optIn -> arguments.add("-opt-in=" + optIn));
        addKaptArguments(arguments, kaptOptions, options.annotationProcessorOptions());
        List<Path> compilationEntries = entries(compilationClasspath);
        if (!compilationEntries.isEmpty()) {
            arguments.add("-classpath");
            arguments.add(joinedPath(compilationEntries, pathSeparator));
        }
        if (options.friendPath() != null) {
            arguments.add("-Xfriend-paths=" + options.friendPath());
        }
        arguments.add("-module-name");
        arguments.add(options.moduleName());
        arguments.add("-d");
        arguments.add(outputDirectory.toString());
        sources.forEach(source -> arguments.add(source.toString()));
        return List.copyOf(arguments);
    }

    private static void addKaptArguments(
            List<String> arguments,
            KotlinKaptOptions options,
            Map<String, String> annotationProcessorOptions) {
        if (options == null) {
            return;
        }
        arguments.add("-Xplugin=" + options.pluginJar());
        addPluginOption(arguments, "aptMode", "stubsAndApt");
        addPluginOption(arguments, "sources", options.generatedSourcesDirectory().toString());
        addPluginOption(arguments, "classes", options.generatedClassesDirectory().toString());
        addPluginOption(arguments, "stubs", options.stubsDirectory().toString());
        options.processorClasspath().entries().forEach(path ->
                addPluginOption(arguments, "apclasspath", path.toAbsolutePath().normalize().toString()));
        KotlinAnnotationProcessorOptions processorOptions =
                new KotlinAnnotationProcessorOptions(annotationProcessorOptions);
        if (!processorOptions.isEmpty()) {
            addPluginOption(arguments, "apoptions", processorOptions.encoded());
        }
        addPluginOption(arguments, "includeCompileClasspath", "false");
        addPluginOption(arguments, "correctErrorTypes", "true");
        addPluginOption(arguments, "mapDiagnosticLocations", "true");
    }

    private static void addPluginOption(
            List<String> arguments,
            String name,
            String value) {
        arguments.add("-P");
        arguments.add("plugin:org.jetbrains.kotlin.kapt3:" + name + "=" + value);
    }

    private static void addVersion(
            List<String> arguments,
            String name,
            String value) {
        if (!value.isEmpty()) {
            arguments.add(name);
            arguments.add(value);
        }
    }

    private static List<Path> entries(Classpath classpath) {
        return classpath == null
                ? List.of()
                : classpath.entries().stream().map(Path::normalize).toList();
    }

    private static String joinedPath(
            List<Path> entries,
            String pathSeparator) {
        StringJoiner joiner = new StringJoiner(pathSeparator);
        entries.forEach(entry -> joiner.add(entry.toString()));
        return joiner.toString();
    }
}
