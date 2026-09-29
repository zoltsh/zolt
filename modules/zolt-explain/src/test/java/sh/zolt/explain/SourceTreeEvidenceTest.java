package sh.zolt.explain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SourceTreeEvidenceTest {
    @TempDir
    private Path tempDir;

    @Test
    void doesNotFollowSourceTreeLinks() throws IOException {
        Path linkedTarget = tempDir.resolve("elsewhere");
        Files.createDirectories(linkedTarget);
        Files.writeString(linkedTarget.resolve("Outside.java"), "class Outside {}\n");
        Path sourceRoot = tempDir.resolve("src/main/kotlin");
        Files.createDirectories(sourceRoot);
        try {
            Files.createSymbolicLink(sourceRoot.resolve("linked"), linkedTarget);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        SourceTreeEvidence evidence = SourceTreeEvidence.inspect(
                "test",
                tempDir,
                List.of("src/main/kotlin"),
                List.of());

        assertTrue(evidence.sourceLinksPresent());
        assertFalse(evidence.mainJavaSourcesPresent(),
                "the traversal must not classify Java sources behind the link");
    }
}
