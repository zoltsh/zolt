package sh.zolt.build.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
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
        if (!options.annotationDefaultTargetMode().isEmpty()) {
            arguments.add("-Xannotation-default-target=" + options.annotationDefaultTargetMode());
        }
        if (!options.assertionMode().isEmpty()) {
            arguments.add("-Xassertions=" + options.assertionMode());
        }
        if (!options.jspecifyAnnotationsMode().isEmpty()) {
            arguments.add("-Xjspecify-annotations=" + options.jspecifyAnnotationsMode());
        }
        if (!options.jsr305Mode().isEmpty()) {
            arguments.add("-Xjsr305=" + options.jsr305Mode());
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
