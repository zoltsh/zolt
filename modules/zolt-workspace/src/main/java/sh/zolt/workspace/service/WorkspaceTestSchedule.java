package sh.zolt.workspace.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Decides the order members are handed to the test pool.
 *
 * <p>The pool drains in submission order, so a long member that starts last leaves the machine
 * running a single JVM at the tail of the run. Submitting the heaviest members first lets the short
 * ones fill the gaps behind them.
 *
 * <p>Ordering only affects <em>submission</em>. Results are scattered back to their original member
 * positions by {@link WorkspaceTestExecutor}, so reporting order never changes.
 */
final class WorkspaceTestSchedule {
    private WorkspaceTestSchedule() {
    }

    /**
     * Submission order as indices into {@code memberPaths}, heaviest first.
     *
     * <p>Equal weights keep their original relative order, so the schedule is stable and a repeated
     * run submits the same sequence.
     */
    static List<Integer> order(List<String> memberPaths, Map<String, Integer> weights) {
        return IntStream.range(0, memberPaths.size())
                .boxed()
                .sorted(Comparator
                        .comparingInt((Integer index) ->
                                -weights.getOrDefault(memberPaths.get(index), 0))
                        .thenComparingInt(index -> index))
                .toList();
    }

    /**
     * Estimated member cost, keyed by member path.
     *
     * <p>No per-member duration history is persisted today, so this counts test sources as a stand-in.
     * Unreadable trees weigh zero and sort last, which costs at most one badly placed member.
     */
    static Map<String, Integer> testSourceWeights(
            List<String> memberPaths,
            Map<String, WorkspaceMember> membersByPath) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String memberPath : memberPaths) {
            WorkspaceMember member = membersByPath.get(memberPath);
            weights.put(memberPath, member == null ? 0 : testSourceCount(member));
        }
        return weights;
    }

    private static int testSourceCount(WorkspaceMember member) {
        int total = 0;
        for (String testRoot : member.config().build().testSources()) {
            total += sourceCount(member.directory().resolve(testRoot));
        }
        return total;
    }

    private static int sourceCount(Path root) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return (int) paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .count();
        } catch (IOException | RuntimeException exception) {
            return 0;
        }
    }
}
