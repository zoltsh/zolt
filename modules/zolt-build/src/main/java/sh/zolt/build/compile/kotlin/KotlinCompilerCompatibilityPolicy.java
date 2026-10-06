package sh.zolt.build.compile.kotlin;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.KotlinCompilationScope;
import sh.zolt.project.CompilerSettings;
import sh.zolt.project.ProjectConfig;

/** Version boundary for the compiler and option combinations qualified by the Kotlin preview. */
public final class KotlinCompilerCompatibilityPolicy {
    private static final Pattern STABLE_VERSION = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)");
    private static final int SUPPORTED_MAJOR = 2;
    private static final int SUPPORTED_MINOR = 2;
    private static final Set<String> QUALIFIED_STANDALONE_OPTIONS = Set.of(
            "-Wextra",
            "-Xreport-all-warnings",
            "-Xrender-internal-diagnostic-names",
            "-Xcontext-sensitive-resolution",
            "-Xcontext-receivers",
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
            "-Xno-inline",
            "-Xuse-inline-scopes-numbers",
            "-Xuse-14-inline-classes-mangling-scheme",
            "-Xenhanced-coroutines-debugging",
            "-Xsanitize-parentheses",
            "-Xmultifile-parts-inherit",
            "-Xvalidate-bytecode",
            "-Xindy-allow-annotated-lambdas",
            "-Xgenerate-strict-metadata-version",
            "-Xannotations-in-metadata",
            "-Xuse-type-table",
            "-Xuse-old-class-files-reading",
            "-Xskip-metadata-version-check",
            "-Xskip-prerelease-check",
            "-Xallow-kotlin-package",
            "-Xuse-k2-kapt",
            "-Xjvm-enable-preview",
            "-Xallow-unstable-dependencies",
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions");
    private static final List<String> QUALIFIED_OPTION_PREFIXES = List.of(
            "-Xexplicit-api=",
            "-Xstring-concat=",
            "-Xlambdas=",
            "-Xsam-conversions=",
            "-Xannotation-default-target=",
            "-Xassertions=",
            "-Xreturn-value-checker=",
            "-Xbackend-threads=",
            "-Xjspecify-annotations=",
            "-Xjsr305=",
            "-Xsupport-compatqual-checker-framework-annotations=",
            "-Xabi-stability=",
            "-Xnullability-annotations=",
            "-Xwarning-level=");

    private KotlinCompilerCompatibilityPolicy() {
    }

    /**
     * Rejects configured compiler arguments before generated-output work or cache reuse can begin.
     *
     * <p>Processor {@code -A} options still enter the Kotlin compiler through KAPT, so they are part
     * of the same qualified compiler-option contract.
     */
    public static void requireConfiguredOptionsSupported(
            ProjectConfig config,
            KotlinCompilationScope scope) {
        Objects.requireNonNull(config, "Project configuration is required.");
        KotlinCompilationScope compilationScope = Objects.requireNonNull(
                scope,
                "Kotlin compilation scope is required.");
        CompilerSettings compiler = config.compilerSettings();
        List<String> arguments = compilationScope == KotlinCompilationScope.MAIN
                ? compiler.args()
                : compiler.testArgs();
        String qualifiedOption = arguments.stream()
                .filter(KotlinCompilerCompatibilityPolicy::requiresQualifiedCompiler)
                .findFirst()
                .orElse("");
        if (qualifiedOption.isEmpty()) {
            return;
        }
        requireSupportedVersion(
                compiler.kotlinVersion(),
                compilationScope,
                argumentsPath(compilationScope) + " requests `" + qualifiedOption + "`");
    }

    /** Rejects a selected compiler outside the stable series exercised by this preview. */
    public static void requireSupportedToolchain(
            String version,
            KotlinCompilationScope scope) {
        requireSupportedVersion(
                version,
                Objects.requireNonNull(scope, "Kotlin compilation scope is required."),
                "the selected Kotlin compiler is `" + normalize(version) + "`");
    }

    private static void requireSupportedVersion(
            String version,
            KotlinCompilationScope scope,
            String context) {
        String normalized = normalize(version);
        Matcher matcher = STABLE_VERSION.matcher(normalized);
        if (matcher.matches()
                && Integer.parseInt(matcher.group(1)) == SUPPORTED_MAJOR
                && Integer.parseInt(matcher.group(2)) == SUPPORTED_MINOR) {
            return;
        }
        throw new KotlinCompileException(
                "Kotlin " + scope.label() + " compilation is not supported when " + context
                        + " with [toolchain.kotlin].version `" + normalized + "`. "
                        + "The bounded compiler-option contract is qualified for stable Kotlin 2.2.x "
                        + "compilers. Select a stable 2.2.x compiler and matching kotlin-stdlib, run "
                        + "`zolt resolve`, and retry.");
    }

    private static String argumentsPath(KotlinCompilationScope scope) {
        return scope == KotlinCompilationScope.MAIN
                ? "[compiler].args"
                : "[compiler.test].args";
    }

    private static boolean requiresQualifiedCompiler(String argument) {
        return QUALIFIED_STANDALONE_OPTIONS.contains(argument)
                || QUALIFIED_OPTION_PREFIXES.stream().anyMatch(argument::startsWith);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "<missing>" : value.strip();
    }
}
