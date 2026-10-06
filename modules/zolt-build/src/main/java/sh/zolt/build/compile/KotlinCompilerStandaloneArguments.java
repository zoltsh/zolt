package sh.zolt.build.compile;

import java.util.Set;

/** Catalogs the bounded, value-free Kotlin compiler arguments. */
final class KotlinCompilerStandaloneArguments {
    private static final Set<String> SUPPORTED = Set.of(
            "-parameters",
            "-Werror",
            "-nowarn",
            "-Wextra",
            "-Xreport-all-warnings",
            "-Xrender-internal-diagnostic-names",
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
            "-Xjvm-enable-preview",
            "-Xallow-unstable-dependencies",
            "-Xno-param-assertions");

    private KotlinCompilerStandaloneArguments() {
    }

    static boolean supports(String argument) {
        return SUPPORTED.contains(argument);
    }
}
