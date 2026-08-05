package sh.zolt.workspace.testpool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.test.runtime.TestRunException;
import org.junit.jupiter.api.Test;

final class WorkspaceTestConcurrencyTest {
    @Test
    void adaptiveOversubscribesCoresByHalf() {
        assertEquals(6, WorkspaceTestConcurrency.adaptiveWorkers(4));
        assertEquals(12, WorkspaceTestConcurrency.adaptiveWorkers(8));
        assertEquals(21, WorkspaceTestConcurrency.adaptiveWorkers(14));
        assertEquals(24, WorkspaceTestConcurrency.adaptiveWorkers(16));
    }

    @Test
    void adaptiveKeepsAtLeastTwoWorkersOnTinyMachines() {
        assertEquals(2, WorkspaceTestConcurrency.adaptiveWorkers(1));
        assertEquals(3, WorkspaceTestConcurrency.adaptiveWorkers(2));
    }

    @Test
    void adaptiveStopsAtTheHardCeiling() {
        assertEquals(
                WorkspaceTestConcurrency.MAX_WORKERS,
                WorkspaceTestConcurrency.adaptiveWorkers(256));
    }

    @Test
    void adaptiveNeverExceedsTheMemberCount() {
        assertEquals(3, WorkspaceTestConcurrency.adaptive().workersFor(3, 14));
        assertEquals(21, WorkspaceTestConcurrency.adaptive().workersFor(203, 14));
    }

    @Test
    void requestedWidthWinsOverTheAdaptiveDefault() {
        assertEquals(8, WorkspaceTestConcurrency.of(8).workersFor(203, 14));
        assertEquals(1, WorkspaceTestConcurrency.of(1).workersFor(203, 14));
    }

    @Test
    void requestedWidthIsStillCappedByTheMemberCount() {
        assertEquals(2, WorkspaceTestConcurrency.of(32).workersFor(2, 14));
    }

    @Test
    void emptySelectionStillResolvesToOneWorker() {
        assertEquals(1, WorkspaceTestConcurrency.adaptive().workersFor(0, 14));
    }

    @Test
    void parsesAnExplicitWorkerCount() {
        WorkspaceTestConcurrency concurrency = WorkspaceTestConcurrency.fromCli("12");

        assertFalse(concurrency.isAdaptive());
        assertEquals(12, concurrency.workersFor(203, 14));
    }

    @Test
    void treatsMissingAndBlankValuesAsAdaptive() {
        assertTrue(WorkspaceTestConcurrency.fromCli(null).isAdaptive());
        assertTrue(WorkspaceTestConcurrency.fromCli("   ").isAdaptive());
        assertTrue(WorkspaceTestConcurrency.adaptive().isAdaptive());
    }

    @Test
    void rejectsZeroAndNegativeWorkerCounts() {
        assertEquals(
                "Invalid --test-workers `0`. Use a positive integer.",
                assertThrows(TestRunException.class, () -> WorkspaceTestConcurrency.fromCli("0"))
                        .getMessage());
        assertEquals(
                "Invalid --test-workers `-3`. Use a positive integer.",
                assertThrows(TestRunException.class, () -> WorkspaceTestConcurrency.fromCli("-3"))
                        .getMessage());
    }

    @Test
    void rejectsValuesThatAreNotNumbers() {
        assertEquals(
                "Invalid --test-workers `many`. Use a positive integer.",
                assertThrows(TestRunException.class, () -> WorkspaceTestConcurrency.fromCli("many"))
                        .getMessage());
    }

    @Test
    void rejectsWorkerCountsAboveTheHardCeiling() {
        assertEquals(
                "Invalid --test-workers `65`. Use a value between 1 and 64.",
                assertThrows(TestRunException.class, () -> WorkspaceTestConcurrency.fromCli("65"))
                        .getMessage());
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals(6, WorkspaceTestConcurrency.fromCli(" 6 ").workersFor(203, 14));
    }
}
