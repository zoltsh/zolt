package sh.zolt.build.compile;

import java.util.List;
import sh.zolt.build.KotlinCompileException;

/** Validates the bounded enum-valued Kotlin compiler arguments. */
final class KotlinCompilerModeArguments {
    private KotlinCompilerModeArguments() {
    }

    static String jvmDefault(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-jvm-default=",
                "JVM-default",
                List.of("enable", "no-compatibility", "disable"),
                "Use `-jvm-default=enable`, `-jvm-default=no-compatibility`, or"
                        + " `-jvm-default=disable`, or remove the argument.");
    }

    static String explicitApi(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xexplicit-api=",
                "explicit-API",
                List.of("strict", "warning", "disable"),
                "Use `-Xexplicit-api=strict`, `-Xexplicit-api=warning`, or"
                        + " `-Xexplicit-api=disable`, or remove the argument.");
    }

    static String stringConcat(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xstring-concat=",
                "string-concatenation",
                List.of("indy-with-constants", "indy", "inline"),
                "Use `-Xstring-concat=indy-with-constants`, `-Xstring-concat=indy`, or"
                        + " `-Xstring-concat=inline`, or remove the argument.");
    }

    static String lambda(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xlambdas=",
                "lambda-generation",
                List.of("class", "indy"),
                "Use `-Xlambdas=class` or `-Xlambdas=indy`, or remove the argument.");
    }

    static String samConversion(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xsam-conversions=",
                "SAM-conversion",
                List.of("class", "indy"),
                "Use `-Xsam-conversions=class` or `-Xsam-conversions=indy`, or remove the argument.");
    }

    static String annotationDefaultTarget(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xannotation-default-target=",
                "annotation-default-target",
                List.of("first-only", "first-only-warn", "param-property"),
                "Use `-Xannotation-default-target=first-only`,"
                        + " `-Xannotation-default-target=first-only-warn`, or"
                        + " `-Xannotation-default-target=param-property`, or remove the argument.");
    }

    static String assertions(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xassertions=",
                "assertion",
                List.of("always-enable", "always-disable", "jvm", "legacy"),
                "Use `-Xassertions=always-enable`, `-Xassertions=always-disable`,"
                        + " `-Xassertions=jvm`, or `-Xassertions=legacy`, or remove the argument.");
    }

    static String jspecifyAnnotations(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xjspecify-annotations=",
                "JSpecify-annotation",
                List.of("ignore", "warn", "strict"),
                "Use `-Xjspecify-annotations=ignore`, `-Xjspecify-annotations=warn`, or"
                        + " `-Xjspecify-annotations=strict`, or remove the argument.");
    }

    static String jsr305(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xjsr305=",
                "JSR-305",
                List.of("ignore", "warn", "strict"),
                "Use `-Xjsr305=ignore`, `-Xjsr305=warn`, or `-Xjsr305=strict`, or remove"
                        + " the argument.");
    }

    static String compatqualAnnotations(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xsupport-compatqual-checker-framework-annotations=",
                "Checker Framework compatqual-annotation",
                List.of("enable", "disable"),
                "Use `-Xsupport-compatqual-checker-framework-annotations=enable` or"
                        + " `-Xsupport-compatqual-checker-framework-annotations=disable`, or"
                        + " remove the argument.");
    }

    static String abiStability(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xabi-stability=",
                "ABI-stability",
                List.of("stable", "unstable"),
                "Use `-Xabi-stability=stable` or `-Xabi-stability=unstable`, or remove"
                        + " the argument.");
    }

    private static String require(
            KotlinCompilationScope scope,
            String argument,
            String prefix,
            String label,
            List<String> allowed,
            String remediation) {
        String value = argument.substring(prefix.length());
        if (!allowed.contains(value)) {
            throw new KotlinCompileException(
                    "Kotlin " + scope.label() + " compilation is not supported when "
                            + argumentsPath(scope)
                            + " contains invalid Kotlin " + label + " argument `" + argument + "`. "
                            + remediation);
        }
        return value;
    }

    private static String argumentsPath(KotlinCompilationScope scope) {
        return scope == KotlinCompilationScope.MAIN
                ? "[compiler].args"
                : "[compiler.test].args";
    }
}
