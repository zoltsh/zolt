package sh.zolt.build.packaging.layout;

import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageArchiveWriter;
import sh.zolt.build.packaging.PackageMergeDecision;
import sh.zolt.project.UberDuplicatePolicy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class UberJarEntryWriter {
    private final PackageArchiveWriter archive;
    private final UberJarMergeAccumulator merges;
    private final UberDuplicatePolicy duplicatePolicy;
    private final Set<String> entries = new HashSet<>();
    private final List<PackageMergeDecision> overrideDecisions = new ArrayList<>();

    UberJarEntryWriter(
            PackageArchiveWriter archive,
            UberJarMergeAccumulator merges,
            UberDuplicatePolicy duplicatePolicy) {
        this.archive = archive;
        this.merges = merges;
        this.duplicatePolicy = duplicatePolicy;
    }

    boolean writeOrCollectEntry(String name, byte[] content, String source) throws IOException {
        if (merges.accepts(name)) {
            if (entries.contains(name)) {
                throw duplicateEntry(name, source);
            }
            merges.add(name, content, source);
            return false;
        }
        return writeEntry(name, content, source);
    }

    boolean writeEntry(String name, byte[] content, String source) throws IOException {
        if (!entries.add(name)) {
            if (duplicatePolicy == UberDuplicatePolicy.FIRST_WINS) {
                overrideDecisions.add(new PackageMergeDecision(
                        "overridden-duplicate", name, Optional.empty(), List.of(source)));
                return false;
            }
            throw duplicateEntry(name, source);
        }
        archive.writeParentDirectories(name);
        archive.writeEntry(name, content);
        return true;
    }

    int writeMergedEntries() throws IOException {
        return merges.writeEntries(archive, entries);
    }

    List<PackageMergeDecision> decisions() {
        List<PackageMergeDecision> decisions = new ArrayList<>(overrideDecisions);
        decisions.addAll(merges.decisions());
        return decisions;
    }

    private static PackageException duplicateEntry(String name, String source) {
        return new PackageException(
                "Duplicate uber jar entry `"
                        + name
                        + "` while merging "
                        + source
                        + ". Move one dependency out of the runtime classpath, set [package].duplicates ="
                        + " \"first-wins\", or use the default `jar` package mode.");
    }
}
