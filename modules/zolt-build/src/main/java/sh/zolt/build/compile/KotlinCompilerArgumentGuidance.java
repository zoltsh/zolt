package sh.zolt.build.compile;

/** Keeps the bounded Kotlin compiler-argument remediation readable and centralized. */
final class KotlinCompilerArgumentGuidance {
    private KotlinCompilerArgumentGuidance() {
    }

    static String supportedArguments() {
        return "Use only a compatible, duplicate-free subset of `-parameters`, `-nowarn`,"
                + " `-Werror`, `-Wextra`, `-Xreport-all-warnings`, `-progressive`,"
                + " `-Xrender-internal-diagnostic-names`,"
                + " `-Xcontext-sensitive-resolution`,"
                + " `-Xcontext-parameters`, `-Xwhen-guards`, `-Xmulti-dollar-interpolation`,"
                + " `-Xnon-local-break-continue`,"
                + " `-Xnested-type-aliases`,"
                + " `-Xannotation-target-all`, `-Xjvm-expose-boxed`,"
                + " `-Xconsistent-data-class-copy-visibility`, `-Xemit-jvm-type-annotations`,"
                + " `-Xno-new-java-annotation-targets`, `-Xno-source-debug-extension`,"
                + " `-Xno-unified-null-checks`, `-Xno-optimize`, `-Xno-inline`,"
                + " `-Xuse-inline-scopes-numbers`,"
                + " `-Xuse-14-inline-classes-mangling-scheme`,"
                + " `-Xenhanced-coroutines-debugging`, `-Xsanitize-parentheses`,"
                + " `-Xmultifile-parts-inherit`,"
                + " `-Xvalidate-bytecode`,"
                + " `-Xindy-allow-annotated-lambdas` with `-Xlambdas=indy`,"
                + " `-Xgenerate-strict-metadata-version`, `-Xannotations-in-metadata`,"
                + " `-Xuse-type-table`, `-Xjvm-enable-preview`, `-Xallow-unstable-dependencies`,"
                + " `-Xno-param-assertions`, `-Xno-call-assertions`,"
                + " `-Xno-receiver-assertions`,"
                + " one `-Xbackend-threads=<0..256>`,"
                + " `-language-version <major.minor>`, `-api-version <major.minor>`, and one"
                + " `-jvm-default=<mode>`, plus repeatable"
                + " `-opt-in=<qualified.annotation.Name>` arguments and one"
                + " `-Xexplicit-api=<mode>`, one `-Xstring-concat=<mode>`, one"
                + " `-Xlambdas=<mode>`, one `-Xsam-conversions=<mode>`, one"
                + " `-Xannotation-default-target=<mode>`, one `-Xassertions=<mode>`, one"
                + " `-Xreturn-value-checker=<mode>`, one"
                + " `-Xjspecify-annotations=<mode>`, one `-Xjsr305=<mode>`, one"
                + " `-Xsupport-compatqual-checker-framework-annotations=<mode>`, and one"
                + " `-Xabi-stability=<mode>`, plus distinct"
                + " repeatable `-Xnullability-annotations=@package.name:<mode>` and"
                + " `-Xwarning-level=DIAGNOSTIC_NAME:<level>` arguments, plus distinct"
                + " `-Akey=value` options when an annotation processor lane is configured; otherwise keep this"
                + " source set Java-only.";
    }
}
