package sh.zolt.build.junit;

import sh.zolt.test.TestInventoryEntry;
import sh.zolt.test.TestSelection;
import sh.zolt.test.shard.TestWorkerPoolPlan;
import java.util.List;

final class PlainJunitWorkerPoolSupport {
    private PlainJunitWorkerPoolSupport() {
    }

    static TestSelection workerSelection(
            TestSelection selection,
            TestInventoryEntry entry) {
        List<TestSelection.MethodSelector> methodSelectors =
                selection.methodSelectors().stream()
                        .filter(method -> method.className()
                                .equals(entry.className()))
                        .toList();
        return TestSelection.fromFields(
                methodSelectors.isEmpty()
                        ? List.of(entry.className())
                        : List.of(),
                methodSelectors,
                List.of(),
                selection.includedTags(),
                selection.excludedTags());
    }

    static List<String> workerIds(
            TestWorkerPoolPlan workerPoolPlan) {
        int workers = workerPoolPlan.waves().stream()
                .mapToInt(wave -> wave.entries().size())
                .max()
                .orElse(0);
        return java.util.stream.IntStream.range(0, workers)
                .mapToObj(index -> "worker-" + (index + 1))
                .toList();
    }

    static void closeSlots(
            List<PlainJunitWorkerSlot> slots,
            RuntimeException failure) {
        RuntimeException firstCloseFailure = null;
        for (PlainJunitWorkerSlot slot : slots) {
            try {
                slot.close();
            } catch (RuntimeException closeFailure) {
                if (failure != null) {
                    failure.addSuppressed(closeFailure);
                } else if (firstCloseFailure == null) {
                    firstCloseFailure = closeFailure;
                } else {
                    firstCloseFailure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure == null && firstCloseFailure != null) {
            throw firstCloseFailure;
        }
    }

    static void abortSlots(
            List<PlainJunitWorkerSlot> slots,
            RuntimeException failure) {
        for (PlainJunitWorkerSlot slot : slots) {
            try {
                slot.abort();
            } catch (RuntimeException abortFailure) {
                failure.addSuppressed(abortFailure);
            }
        }
    }
}
