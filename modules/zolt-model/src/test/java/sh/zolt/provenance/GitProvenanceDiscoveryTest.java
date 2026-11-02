package sh.zolt.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class GitProvenanceDiscoveryTest {
    private static final String OUTER = "1111111111111111111111111111111111111111";
    private static final String INNER = "2222222222222222222222222222222222222222";
    private final GitProvenanceReader reader = new GitProvenanceReader();
    @TempDir private Path root;

    @Test
    void memberBuildProvenanceUsesTheEnclosingRepositoryCommit() throws IOException {
        repository(root, OUTER);
        Path member = Files.createDirectories(root.resolve("apps/zolt"));

        var provenance = BuildProvenanceSource.system("1.0.0", path -> Optional.empty())
                .read(member, Map.of(), Clock.systemUTC());

        assertEquals(Optional.of(OUTER), provenance.git().commitSha());
        assertEquals(Optional.of("main"), provenance.git().branch());
    }

    @Test
    void memberFindsAnEnclosingDetachedCheckout() throws IOException {
        Path git = repository(root, OUTER);
        Files.writeString(git.resolve("HEAD"), INNER + "\n");
        Path member = Files.createDirectories(root.resolve("apps/zolt"));

        var provenance = reader.read(member).orElseThrow();

        assertEquals(Optional.of(INNER), provenance.commitSha());
        assertTrue(provenance.detached());
        assertTrue(provenance.branch().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void linkedWorktreeMemberUsesCommonLooseRefBeforeOlderPackedRef(boolean absoluteGitDir) throws IOException {
        Path common = repository(root.resolve("main"), OUTER);
        Files.writeString(common.resolve("packed-refs"), INNER + " refs/heads/main\n");
        Path git = Files.createDirectories(common.resolve("worktrees/release"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Files.writeString(git.resolve("commondir"), "../..\n");
        Path checkout = Files.createDirectory(root.resolve("checkout"));
        Files.writeString(checkout.resolve(".git"), "gitdir: "
                + (absoluteGitDir ? git : checkout.relativize(git)) + "\n");
        Path member = Files.createDirectories(checkout.resolve("apps/zolt"));

        assertEquals(Optional.of(OUTER), reader.read(member).orElseThrow().commitSha());
    }

    @Test
    void nearestNestedRepositoryWins() throws IOException {
        repository(root, OUTER);
        Path nested = Files.createDirectories(root.resolve("vendor/nested"));
        repository(nested, INNER);
        Path member = Files.createDirectories(nested.resolve("app"));

        assertEquals(Optional.of(INNER), reader.read(member).orElseThrow().commitSha());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a gitdir", "gitdir: ../missing", "gitdir: "})
    void malformedNestedMarkerNeverFallsBackToOuterRepository(String marker) throws IOException {
        repository(root, OUTER);
        Path nested = Files.createDirectories(root.resolve("vendor/nested"));
        Files.writeString(nested.resolve(".git"), marker);
        Path member = Files.createDirectory(nested.resolve("app"));

        assertTrue(reader.read(member).isEmpty());
    }

    @Test
    void invalidWorktreePathNeverEscapesTheNestedBoundary() throws IOException {
        repository(root, OUTER);
        Path nested = Files.createDirectories(root.resolve("vendor/nested"));
        Files.writeString(nested.resolve(".git"), "gitdir: invalid" + (char) 0 + "path");

        assertTrue(reader.read(nested).isEmpty());
    }

    @Test
    void incompleteNestedRepositoryDoesNotBorrowTheOuterCommit() throws IOException {
        repository(root, OUTER);
        Path nested = Files.createDirectories(root.resolve("vendor/nested"));
        Files.createDirectory(nested.resolve(".git"));
        Path member = Files.createDirectory(nested.resolve("app"));

        assertTrue(reader.read(member).orElseThrow().commitSha().isEmpty());
    }

    @Test
    void danglingNestedMarkerDoesNotBorrowTheOuterCommit() throws IOException {
        repository(root, OUTER);
        Path nested = Files.createDirectories(root.resolve("vendor/nested"));
        Files.createSymbolicLink(nested.resolve(".git"), Path.of("missing-git"));

        assertTrue(reader.read(nested).isEmpty());
    }

    @Test
    void aliasedMemberUsesItsPhysicalRepository() throws IOException {
        Path outer = Files.createDirectory(root.resolve("outer"));
        repository(outer, OUTER);
        Path actual = Files.createDirectory(root.resolve("actual"));
        repository(actual, INNER);
        Path member = Files.createDirectories(actual.resolve("apps/zolt"));
        Path alias = outer.resolve("linked-member");
        Files.createSymbolicLink(alias, member);

        assertEquals(Optional.of(INNER), reader.read(alias).orElseThrow().commitSha());
    }

    @Test
    void unrelatedAndMissingProjectDirectoriesRemainWithoutProvenance() throws IOException {
        repository(root.resolve("sibling"), OUTER);
        Path unrelated = Files.createDirectories(root.resolve("unrelated/app"));

        assertTrue(reader.read(unrelated).isEmpty());
        assertTrue(reader.read(root.resolve("missing")).isEmpty());
    }

    private static Path repository(Path checkout, String sha) throws IOException {
        Path git = Files.createDirectories(checkout.resolve(".git"));
        Files.writeString(git.resolve("HEAD"), "ref: refs/heads/main\n");
        Path ref = git.resolve("refs/heads/main");
        Files.createDirectories(ref.getParent());
        Files.writeString(ref, sha + "\n");
        return git;
    }
}
