package sh.zolt.build.compile;

import sh.zolt.build.KotlinCompileException;

/** Validates repeatable Kotlin compiler arguments and exposes their duplicate keys. */
final class KotlinCompilerRepeatableArgumentPolicy {
    private KotlinCompilerRepeatableArgumentPolicy() {
    }

    static String nullabilityAnnotation(
            KotlinCompilationScope scope,
            String argument) {
        String value = argument.substring("-Xnullability-annotations=".length());
        if (!value.matches(
                "@[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*:(ignore|warn|strict)")) {
            throw unsupported(
                    scope,
                    argumentsPath(scope)
                            + " contains invalid Kotlin nullability-annotation argument `"
                            + argument + "`",
                    "Use `-Xnullability-annotations=@package.name:ignore`, `:warn`, or"
                            + " `:strict`, or remove the argument.");
        }
        return value;
    }

    static String nullabilityAnnotationPackage(String value) {
        return value.substring(1, value.indexOf(':'));
    }

    static String warningLevel(
            KotlinCompilationScope scope,
            String argument) {
        String value = argument.substring("-Xwarning-level=".length());
        if (!value.matches("[A-Z][A-Z0-9_]*:(error|warning|disabled)")) {
            throw unsupported(
                    scope,
                    argumentsPath(scope)
                            + " contains invalid Kotlin warning-level argument `" + argument + "`",
                    "Use `-Xwarning-level=DIAGNOSTIC_NAME:error`, `:warning`, or `:disabled`"
                            + " with an uppercase Kotlin diagnostic name, or remove the argument.");
        }
        return value;
    }

    static String warningDiagnostic(String warningLevel) {
        return warningLevel.substring(0, warningLevel.indexOf(':'));
    }

    static String optIn(
            KotlinCompilationScope scope,
            String argument) {
        String value = argument.substring("-opt-in=".length());
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            throw unsupported(
                    scope,
                    argumentsPath(scope)
                            + " contains invalid Kotlin opt-in argument `" + argument + "`",
                    "Use `-opt-in=<qualified.annotation.Name>` or remove the argument.");
        }
        return value;
    }

    private static String argumentsPath(KotlinCompilationScope scope) {
        return scope == KotlinCompilationScope.MAIN
                ? "[compiler].args"
                : "[compiler.test].args";
    }

    private static KotlinCompileException unsupported(
            KotlinCompilationScope scope,
            String reason,
            String remediation) {
        return new KotlinCompileException(
                "Kotlin " + scope.label() + " compilation is not supported when " + reason + ". "
                        + remediation);
    }
}
