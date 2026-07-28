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
