package sh.zolt.build.testruntime.compile;

import java.nio.file.Path;
import java.util.List;
import sh.zolt.build.CompileDiagnostics;
import sh.zolt.build.compile.IncrementalJavacExecution;
import sh.zolt.build.compile.JavacResult;
import sh.zolt.build.incremental.GeneratedOutputAttribution;

/** Combined result of the language compilers participating in one test compile. */
record TestCompileAttempt(
        JavacResult javacResult,
        JavacResult groovyResult,
        JavacResult kotlinResult,
        String mode,
        String fallbackReason,
        CompileDiagnostics diagnostics,
        GeneratedOutputAttribution attribution,
        List<Path> compiledSources) {
    TestCompileAttempt(
            JavacResult javacResult,
            JavacResult groovyResult,
            JavacResult kotlinResult,
            String mode,
            String fallbackReason,
            CompileDiagnostics diagnostics) {
        this(javacResult, groovyResult, kotlinResult, mode, fallbackReason, diagnostics,
                GeneratedOutputAttribution.absent(), List.of());
    }

    int sourceCount() {
        return javacResult.sourceCount()
                + groovyResult.sourceCount()
                + kotlinResult.sourceCount();
    }

    Path outputDirectory() {
        return javacResult.outputDirectory();
    }

    String output() {
        return IncrementalJavacExecution.combinedOutput(
                javacResult.output(),
                IncrementalJavacExecution.combinedOutput(
                        groovyResult.output(),
                        kotlinResult.output()));
    }
}
