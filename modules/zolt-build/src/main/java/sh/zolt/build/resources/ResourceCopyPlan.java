package sh.zolt.build.resources;

import sh.zolt.build.ResourceCopyException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class ResourceCopyPlan {
    record Entry(Path source, Path relative, Optional<byte[]> filtered) {}

    static void copy(Path output, List<Entry> entries, List<Path> copied, List<Path> skipped) {
        Set<Path> current = entries.stream().map(Entry::relative).collect(Collectors.toSet());
        ResourceCopyState.reconcile(output, current);
        try {
            for (Entry entry : entries) {
                Path target = output.resolve(entry.relative());
                Files.createDirectories(target.getParent());
                if (isCurrent(entry, target)) {
                    skipped.add(entry.source());
                } else if (entry.filtered().isPresent()) {
                    Files.write(target, entry.filtered().orElseThrow());
                    copied.add(entry.source());
                } else {
                    Files.copy(entry.source(), target, StandardCopyOption.REPLACE_EXISTING);
                    copied.add(entry.source());
                }
            }
        } catch (IOException exception) {
            throw new ResourceCopyException(
                    "Could not copy resources to " + output
                            + ". Check that the project directories are readable and writable.", exception);
        }
    }

    private static boolean isCurrent(Entry entry, Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }
        if (entry.filtered().isPresent()) {
            byte[] content = entry.filtered().orElseThrow();
            return Files.size(target) == content.length && Arrays.equals(Files.readAllBytes(target), content);
        }
        return Files.size(entry.source()) == Files.size(target) && Files.mismatch(entry.source(), target) == -1L;
    }
}
