package sh.zolt.build.testruntime.execution;

import sh.zolt.build.JavaRunException;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.test.TestSelection;
import java.nio.file.Path;
import java.util.Optional;

final class TestConsoleFailureHandler {
    void throwForFailedRun(
            JavaRunException exception,
            TestSelection selection,
            Optional<Path> reportsDirectory) {
        if (!selection.emptySelection() && noTestsFound(exception.getMessage())) {
            throw noSelectedTestsMatched(exception.getMessage(), exception);
        }
        if (reportsDirectory.isEmpty()) {
            throw exception;
        }
        throw testFailed(exception, reportsDirectory);
    }

    void throwIfSelectedTestsDidNotMatch(String output, TestSelection selection) {
        if (!selection.emptySelection() && noTestsFound(output)) {
            throw noSelectedTestsMatched(output, null);
        }
    }

    void throwIfLauncherDidNotStart(String output) {
        if (output.contains("Cannot create Launcher without at least one TestEngine")) {
            throw new TestRunException(
                    "No test engine is present on the test classpath. "
                            + "Run `zolt add test org.junit.jupiter:junit-jupiter:5.14.4`, "
                            + "then run `zolt test` again.");
        }
    }

    /**
     * Guards against a silent no-op: when test sources compiled but the JUnit Platform
     * discovered zero tests (for example because the resolved {@code junit-platform} launcher
     * version does not match the {@code junit-jupiter} engine version), the run must fail loudly
     * instead of reporting success. Only fires for an unfiltered run — a filtered selection that
     * matches nothing is handled by {@link #throwIfSelectedTestsDidNotMatch}.
     */
    void throwIfCompiledTestsProducedNoTests(String output, TestSelection selection, int compiledTestSourceCount) {
        if (selection.emptySelection() && compiledTestSourceCount > 0 && noTestsFound(output)) {
            throw new TestRunException(
                    "No tests were discovered even though " + compiledTestSourceCount
                            + " test source file(s) compiled. The JUnit Platform found 0 tests, so nothing ran. "
                            + "This usually means no test engine matched the platform launcher — check that "
                            + "your [test.dependencies] engine version (for example org.junit.jupiter:junit-jupiter) "
                            + "lines up with the JUnit Platform, then run `zolt resolve` and `zolt test` again.\n"
                            + output.stripTrailing());
        }
    }

    private static boolean noTestsFound(String message) {
        return message.contains("No tests found")
                || message.contains("Tests found: 0")
                || message.contains("[         0 tests found");
    }

    private static TestRunException noSelectedTestsMatched(String output, Throwable cause) {
        String message = "Selected tests did not match any tests. "
                + "Check --test, --tests, --include-tag, and --exclude-tag values, then run `zolt test` again.\n"
                + output.stripTrailing();
        return cause == null ? new TestRunException(message) : new TestRunException(message, cause);
    }

    private static TestRunException testFailed(JavaRunException exception, Optional<Path> reportsDirectory) {
        String message = exception.getMessage() + "\nTest reports: " + reportsDirectory.orElseThrow();
        return new TestRunException(message, exception);
    }
}
