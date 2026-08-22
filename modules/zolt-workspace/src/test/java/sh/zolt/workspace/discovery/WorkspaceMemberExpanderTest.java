package sh.zolt.workspace.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.manifest.WorkspaceMemberPattern;
import sh.zolt.workspace.WorkspaceConfigException;

final class WorkspaceMemberExpanderTest {
    private final WorkspaceMemberExpander expander = new WorkspaceMemberExpander();

    @TempDir
    private Path tempDir;

    @Test
    void matchesOneSegmentAtEachWildcardAndSortsByUnicodeCodePoint() throws IOException {
        directories("services/zeta/api", "services/alpha/api", "services/alpha/nested/api");

        WorkspaceMemberExpander.Expansion expansion = expander.expand(
                tempDir,
                List.of(pattern("services/*/api")),
                List.of());

        assertEquals(
                List.of("services/alpha/api", "services/zeta/api"),
                expansion.candidates().stream().map(candidate -> candidate.path()).toList());
    }

    @Test
    void wildcardIgnoresDotDirectoriesButExactHiddenPathWorks() throws IOException {
        directories("apps/api", "apps/.hidden");

        WorkspaceMemberExpander.Expansion wildcard = expander.expand(
                tempDir,
                List.of(pattern("apps/*")),
                List.of());
        WorkspaceMemberExpander.Expansion exact = expander.expand(
                tempDir,
                List.of(pattern("apps/.hidden")),
                List.of());

        assertEquals(List.of("apps/api"), paths(wildcard));
        assertEquals(List.of("apps/.hidden"), paths(exact));
    }

    @Test
    void exactLiteralUsesActualCaseOnEveryFilesystem() throws IOException {
        directories("apps/Api");

        WorkspaceConfigException wrongCase = assertThrows(
                WorkspaceConfigException.class,
                () -> expander.expand(
                        tempDir, List.of(pattern("apps/api")), List.of()));
        assertTrue(wrongCase.getMessage().contains("Exact workspace include `apps/api`"));
        assertEquals(
                List.of("apps/Api"),
                paths(expander.expand(
                        tempDir, List.of(pattern("apps/Api")), List.of())));
    }

    @Test
    void overlappingPatternsRetainSortedDeduplicatedEvidence() throws IOException {
        directories("apps/api");

        WorkspaceMemberExpander.Candidate candidate = expander.expand(
                        tempDir,
                        List.of(pattern("apps/*"), pattern("apps/api")),
                        List.of())
                .candidates()
                .getFirst();

        assertEquals(
                List.of("apps/*", "apps/api"),
                candidate.matchedBy().stream().map(Object::toString).toList());
    }

    @Test
    void rejectsLiteralSymlinkTraversalAndDoesNotFollowWildcardSymlinks() throws IOException {
        Path outside = Files.createTempDirectory(tempDir.getParent(), "workspace-outside-");
        Path exactLink = tempDir.resolve("apps/exact");
        Path wildcardLink = tempDir.resolve("apps/wildcard");
        Files.createDirectories(exactLink.getParent());
        try {
            Files.createSymbolicLink(exactLink, outside);
            Files.createSymbolicLink(wildcardLink, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symbolic links unavailable: " + exception.getMessage());
        }

        WorkspaceConfigException exact = assertThrows(
                WorkspaceConfigException.class,
                () -> expander.expand(
                        tempDir, List.of(pattern("apps/exact")), List.of()));
        assertTrue(exact.getMessage().contains("symbolic link"));
        assertTrue(expander.expand(
                        tempDir, List.of(pattern("apps/*")), List.of())
                .candidates()
                .isEmpty());
    }

    @Test
    void recordsEveryExclusionMatchAndStaleExclusion() throws IOException {
        directories("apps/api", "apps/admin");

        WorkspaceMemberExpander.Expansion expansion = expander.expand(
                tempDir,
                List.of(pattern("apps/*")),
                List.of(pattern("apps/admin"), pattern("modules/*")));

        assertEquals(List.of("apps/api"), paths(expansion));
        assertEquals(
                List.of("apps/admin"),
                expansion.excludedBy().get(pattern("apps/admin")).stream()
                        .map(Object::toString)
                        .toList());
        assertTrue(expansion.excludedBy().get(pattern("modules/*")).isEmpty());
    }

    private void directories(String... paths) throws IOException {
        for (String path : paths) {
            Files.createDirectories(tempDir.resolve(path));
        }
    }

    private static List<String> paths(WorkspaceMemberExpander.Expansion expansion) {
        return expansion.candidates().stream().map(candidate -> candidate.path()).toList();
    }

    private static WorkspaceMemberPattern pattern(String value) {
        return new WorkspaceMemberPattern(value);
    }
}
