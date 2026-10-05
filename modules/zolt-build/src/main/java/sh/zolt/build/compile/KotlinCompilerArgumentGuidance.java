package sh.zolt.build.compile;

/** Keeps the bounded Kotlin compiler-argument remediation readable and centralized. */
final class KotlinCompilerArgumentGuidance {
    private KotlinCompilerArgumentGuidance() {
    }

    static String supportedArguments() {
        return "Use only a compatible, duplicate-free subset of `-parameters`, `-nowarn`,"
                + " `-Werror`, `-Wextra`, `-progressive`, `-Xcontext-sensitive-resolution`,"
                + " `-Xcontext-parameters`, `-Xwhen-guards`, `-Xmulti-dollar-interpolation`,"
                + " `-Xnon-local-break-continue`,"
                + " `-Xnested-type-aliases`,"
                + " `-Xannotation-target-all`, `-Xjvm-expose-boxed`,"
                + " `-Xconsistent-data-class-copy-visibility`, `-Xemit-jvm-type-annotations`,"
                + " `-Xno-new-java-annotation-targets`,"
                + " `-language-version <major.minor>`, `-api-version <major.minor>`, and one"
                + " `-jvm-default=<mode>`, plus repeatable"
                + " `-opt-in=<qualified.annotation.Name>` arguments and one"
                + " `-Xexplicit-api=<mode>`, one `-Xstring-concat=<mode>`, one"
                + " `-Xlambdas=<mode>`, one `-Xsam-conversions=<mode>`, one"
                + " `-Xannotation-default-target=<mode>`, one `-Xassertions=<mode>`, one"
                + " `-Xjspecify-annotations=<mode>`, one `-Xjsr305=<mode>`, and distinct"
                + " repeatable `-Xnullability-annotations=@package.name:<mode>` and"
                + " `-Xwarning-level=DIAGNOSTIC_NAME:<level>` arguments; otherwise keep this"
                + " source set Java-only.";
    }
}
