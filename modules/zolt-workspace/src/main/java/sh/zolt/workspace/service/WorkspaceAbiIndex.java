package sh.zolt.workspace.service;

import sh.zolt.build.incremental.IncrementalCompileSummary;
import sh.zolt.build.incremental.IncrementalCompileSummaryReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class WorkspaceAbiIndex {
    private final IncrementalCompileSummaryReader reader =
            new IncrementalCompileSummaryReader();
    private final Map<Path, Optional<IncrementalCompileSummary>> main =
            new LinkedHashMap<>();
    private final Map<Path, Optional<IncrementalCompileSummary>> test =
            new LinkedHashMap<>();
    private int reads;
    private int hits;

    synchronized Optional<IncrementalCompileSummary> main(Path outputDirectory) {
        Path output = outputDirectory.toAbsolutePath().normalize();
        Optional<IncrementalCompileSummary> cached = main.get(output);
        if (cached != null) {
            hits++;
            return cached;
        }
        reads++;
        Optional<IncrementalCompileSummary> summary = reader.readMain(output);
        main.put(output, summary);
        return summary;
    }

    synchronized Optional<IncrementalCompileSummary> test(Path outputDirectory) {
        Path output = outputDirectory.toAbsolutePath().normalize();
        Optional<IncrementalCompileSummary> cached = test.get(output);
        if (cached != null) {
            hits++;
            return cached;
        }
        reads++;
        Optional<IncrementalCompileSummary> summary = reader.readTest(output);
        test.put(output, summary);
        return summary;
    }

    synchronized Optional<IncrementalCompileSummary> refreshMain(Path outputDirectory) {
        Path output = outputDirectory.toAbsolutePath().normalize();
        reads++;
        Optional<IncrementalCompileSummary> summary = reader.readMain(output);
        main.put(output, summary);
        return summary;
    }

    synchronized Optional<IncrementalCompileSummary> refreshTest(Path outputDirectory) {
        Path output = outputDirectory.toAbsolutePath().normalize();
        reads++;
        Optional<IncrementalCompileSummary> summary = reader.readTest(output);
        test.put(output, summary);
        return summary;
    }

    synchronized int reads() {
        return reads;
    }

    synchronized int hits() {
        return hits;
    }
}
