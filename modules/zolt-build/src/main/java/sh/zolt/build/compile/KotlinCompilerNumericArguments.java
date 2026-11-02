package sh.zolt.build.compile;

import sh.zolt.build.KotlinCompileException;

/** Validates bounded numeric Kotlin compiler arguments. */
final class KotlinCompilerNumericArguments {
    private static final String BACKEND_THREADS = "-Xbackend-threads=";
    private static final int MAX_BACKEND_THREADS = 256;

    private KotlinCompilerNumericArguments() {
    }

    static String backendThreads(KotlinCompilationScope scope, String argument) {
        String value = argument.substring(BACKEND_THREADS.length());
        try {
            if (!value.matches("0|[1-9][0-9]*")) {
                throw invalid(scope, argument);
            }
            int threads = Integer.parseInt(value);
            if (threads > MAX_BACKEND_THREADS) {
                throw invalid(scope, argument);
            }
            return value;
        } catch (NumberFormatException exception) {
            throw invalid(scope, argument);
        }
    }

    private static KotlinCompileException invalid(
            KotlinCompilationScope scope,
            String argument) {
        String path = scope == KotlinCompilationScope.MAIN
                ? "[compiler].args"
                : "[compiler.test].args";
        return new KotlinCompileException(
                "Kotlin " + scope.label() + " compilation is not supported when " + path
                        + " contains invalid Kotlin backend-thread argument `" + argument + "`. "
                        + "Use `-Xbackend-threads=0` to match the processor count or an integer from"
                        + " 1 through " + MAX_BACKEND_THREADS + ", or remove the argument.");
    }
}
