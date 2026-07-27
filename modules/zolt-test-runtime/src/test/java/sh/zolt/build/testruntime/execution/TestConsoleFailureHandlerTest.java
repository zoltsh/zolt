package sh.zolt.build.testruntime.execution;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.JavaRunException;
import sh.zolt.test.runtime.TestRunException;
import sh.zolt.test.TestSelection;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class TestConsoleFailureHandlerTest {
    private final TestConsoleFailureHandler handler = new TestConsoleFailureHandler();

    @Test
    void selectedTestFailureWithNoTestsFoundBecomesActionableSelectionError() {
        JavaRunException failure = new JavaRunException("No tests found for request");
        TestSelection selection = TestSelection.fromCli(List.of("com.example.MissingTest"), List.of(), List.of(), List.of());

        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> handler.throwForFailedRun(failure, selection, Optional.empty()));

        assertTrue(exception.getMessage().contains("Selected tests did not match any tests"));
        assertTrue(exception.getMessage().contains("Check --test, --tests, --include-tag, and --exclude-tag"));
        assertSame(failure, exception.getCause());
    }

    @Test
    void reportDirectoryIsAddedToUnselectedConsoleFailure() {
        JavaRunException failure = new JavaRunException("test failed");
        Path reports = Path.of("target/test-reports");

        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> handler.throwForFailedRun(failure, TestSelection.empty(), Optional.of(reports)));

        assertTrue(exception.getMessage().contains("test failed"));
        assertTrue(exception.getMessage().contains("Test reports: target/test-reports"));
        assertSame(failure, exception.getCause());
    }

    @Test
    void unselectedConsoleFailureWithoutReportsIsRethrown() {
        JavaRunException failure = new JavaRunException("test failed");

        JavaRunException exception = assertThrows(
                JavaRunException.class,
                () -> handler.throwForFailedRun(failure, TestSelection.empty(), Optional.empty()));

        assertSame(failure, exception);
    }

    @Test
    void successfulConsoleOutputWithZeroSelectedTestsStillFails() {
        TestSelection selection = TestSelection.fromCli(List.of(), List.of("*MissingTest"), List.of(), List.of());

        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> handler.throwIfSelectedTestsDidNotMatch("[         0 tests found           ]", selection));

        assertTrue(exception.getMessage().contains("Selected tests did not match any tests"));
        assertTrue(exception.getMessage().contains("0 tests found"));
    }

    @Test
    void launcherWithoutTestEngineFailsEvenWhenConsoleExitsCleanly() {
        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> handler.throwIfLauncherDidNotStart(
                        "Cannot create Launcher without at least one TestEngine; consider adding an engine implementation JAR"));

        assertTrue(exception.getMessage().contains("No test engine is present"));
        assertTrue(exception.getMessage().contains("org.junit.jupiter:junit-jupiter"));
    }

    @Test
    void unfilteredRunWithCompiledTestsButZeroTestsFoundFailsLoudly() {
        TestRunException exception = assertThrows(
                TestRunException.class,
                () -> handler.throwIfCompiledTestsProducedNoTests(
                        "[         0 tests found           ]", TestSelection.empty(), 3));

        assertTrue(exception.getMessage().contains("No tests were discovered"));
        assertTrue(exception.getMessage().contains("3 test source file(s) compiled"));
        assertTrue(exception.getMessage().contains("junit-jupiter"));
    }

    @Test
    void unfilteredRunWithTestsFoundDoesNotFail() {
        handler.throwIfCompiledTestsProducedNoTests(
                "[         1 tests found           ]\n[         1 tests successful      ]",
                TestSelection.empty(),
                1);
    }

    @Test
    void zeroTestsFoundWithNoCompiledTestSourcesDoesNotFail() {
        handler.throwIfCompiledTestsProducedNoTests(
                "[         0 tests found           ]", TestSelection.empty(), 0);
    }

    @Test
    void filteredRunWithZeroTestsFoundIsLeftToSelectionHandler() {
        TestSelection selection = TestSelection.fromCli(List.of("com.example.MissingTest"), List.of(), List.of(), List.of());

        handler.throwIfCompiledTestsProducedNoTests(
                "[         0 tests found           ]", selection, 2);
    }
}
