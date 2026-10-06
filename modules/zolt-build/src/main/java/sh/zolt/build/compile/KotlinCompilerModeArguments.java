package sh.zolt.build.compile;

import java.util.Arrays;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.AbiStabilityMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.AnnotationDefaultTargetMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.AssertionMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.ClosureGenerationMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.CompatqualAnnotationsMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.ExplicitApiMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.JvmDefaultMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.NullabilityMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.ReturnValueCheckerMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.StringConcatMode;
import sh.zolt.build.compile.kotlin.KotlinCompilerModes.Value;

/** Validates the bounded enum-valued Kotlin compiler arguments. */
final class KotlinCompilerModeArguments {
    private KotlinCompilerModeArguments() {
    }

    static JvmDefaultMode jvmDefault(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-jvm-default=",
                "JVM-default",
                JvmDefaultMode.class,
                "Use `-jvm-default=enable`, `-jvm-default=no-compatibility`, or"
                        + " `-jvm-default=disable`, or remove the argument.");
    }

    static ExplicitApiMode explicitApi(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xexplicit-api=",
                "explicit-API",
                ExplicitApiMode.class,
                "Use `-Xexplicit-api=strict`, `-Xexplicit-api=warning`, or"
                        + " `-Xexplicit-api=disable`, or remove the argument.");
    }

    static StringConcatMode stringConcat(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xstring-concat=",
                "string-concatenation",
                StringConcatMode.class,
                "Use `-Xstring-concat=indy-with-constants`, `-Xstring-concat=indy`, or"
                        + " `-Xstring-concat=inline`, or remove the argument.");
    }

    static ClosureGenerationMode lambda(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xlambdas=",
                "lambda-generation",
                ClosureGenerationMode.class,
                "Use `-Xlambdas=class` or `-Xlambdas=indy`, or remove the argument.");
    }

    static ClosureGenerationMode samConversion(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xsam-conversions=",
                "SAM-conversion",
                ClosureGenerationMode.class,
                "Use `-Xsam-conversions=class` or `-Xsam-conversions=indy`, or remove the argument.");
    }

    static AnnotationDefaultTargetMode annotationDefaultTarget(
            KotlinCompilationScope scope,
            String argument) {
        return require(
                scope,
                argument,
                "-Xannotation-default-target=",
                "annotation-default-target",
                AnnotationDefaultTargetMode.class,
                "Use `-Xannotation-default-target=first-only`,"
                        + " `-Xannotation-default-target=first-only-warn`, or"
                        + " `-Xannotation-default-target=param-property`, or remove the argument.");
    }

    static AssertionMode assertions(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xassertions=",
                "assertion",
                AssertionMode.class,
                "Use `-Xassertions=always-enable`, `-Xassertions=always-disable`,"
                        + " `-Xassertions=jvm`, or `-Xassertions=legacy`, or remove the argument.");
    }

    static ReturnValueCheckerMode returnValueChecker(
            KotlinCompilationScope scope,
            String argument) {
        return require(
                scope,
                argument,
                "-Xreturn-value-checker=",
                "return-value-checker",
                ReturnValueCheckerMode.class,
                "Use `-Xreturn-value-checker=check`, `-Xreturn-value-checker=full`, or"
                        + " `-Xreturn-value-checker=disable`, or remove the argument.");
    }

    static NullabilityMode jspecifyAnnotations(
            KotlinCompilationScope scope,
            String argument) {
        return require(
                scope,
                argument,
                "-Xjspecify-annotations=",
                "JSpecify-annotation",
                NullabilityMode.class,
                "Use `-Xjspecify-annotations=ignore`, `-Xjspecify-annotations=warn`, or"
                        + " `-Xjspecify-annotations=strict`, or remove the argument.");
    }

    static NullabilityMode jsr305(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xjsr305=",
                "JSR-305",
                NullabilityMode.class,
                "Use `-Xjsr305=ignore`, `-Xjsr305=warn`, or `-Xjsr305=strict`, or remove"
                        + " the argument.");
    }

    static CompatqualAnnotationsMode compatqualAnnotations(
            KotlinCompilationScope scope,
            String argument) {
        return require(
                scope,
                argument,
                "-Xsupport-compatqual-checker-framework-annotations=",
                "Checker Framework compatqual-annotation",
                CompatqualAnnotationsMode.class,
                "Use `-Xsupport-compatqual-checker-framework-annotations=enable` or"
                        + " `-Xsupport-compatqual-checker-framework-annotations=disable`, or"
                        + " remove the argument.");
    }

    static AbiStabilityMode abiStability(KotlinCompilationScope scope, String argument) {
        return require(
                scope,
                argument,
                "-Xabi-stability=",
                "ABI-stability",
                AbiStabilityMode.class,
                "Use `-Xabi-stability=stable` or `-Xabi-stability=unstable`, or remove"
                        + " the argument.");
    }

    private static <T extends Enum<T> & Value> T require(
            KotlinCompilationScope scope,
            String argument,
            String prefix,
            String label,
            Class<T> modeType,
            String remediation) {
        String value = argument.substring(prefix.length());
        return Arrays.stream(modeType.getEnumConstants())
                .filter(mode -> mode.configured() && mode.argumentValue().equals(value))
                .findFirst()
                .orElseThrow(() -> new KotlinCompileException(
                        "Kotlin " + scope.label() + " compilation is not supported when "
                                + argumentsPath(scope)
                                + " contains invalid Kotlin " + label + " argument `" + argument
                                + "`. " + remediation));
    }

    private static String argumentsPath(KotlinCompilationScope scope) {
        return scope == KotlinCompilationScope.MAIN
                ? "[compiler].args"
                : "[compiler.test].args";
    }
}
