package sh.zolt.cli.insight;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.sbom.SbomScopeGroup;
import sh.zolt.sbom.SbomScopeSelection;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cross-check the GitHub dependency-submission action performs on every run: the graph in
 * {@code zolt tree --workspace --format json} must describe the same external relationships as the
 * CycloneDX graph in {@code zolt sbom --workspace}, once both are normalized the same way. The action
 * aborts a submission when they disagree, so a divergence here is a released-contract break rather
 * than a cosmetic difference.
 *
 * <p>Schema 2 emits every locked scope, while {@code sbom} defaults to compile + runtime — so the
 * action passes all four {@code --include-*} flags, and {@link #everyLockedScopeIsSbomIncludable()}
 * pins that those flags really do cover every scope the tree can emit.
 */
final class TreeSbomCrossCheckTest {
    @TempDir
    private Path tempDir;

    @Test
    void theTreeAndSbomGraphsAgreeOnEveryExternalEdge() throws IOException {
        Path workspace = TreeSbomCrossCheckFixture.write(tempDir.resolve("cross-check"));

        String tree = tree(workspace);
        String sbom = sbom(workspace);
        Set<String> firstParty = DependencySubmissionNormalizer.firstPartyNodes(sbom);

        assertEquals(
                DependencySubmissionNormalizer.sbomEdges(sbom, firstParty),
                DependencySubmissionNormalizer.treeEdges(tree, firstParty),
                "tree\n" + tree + "\nsbom\n" + sbom);
    }

    /**
     * Guards the cross-check against passing on two empty graphs, and pins the member-sensitive facts
     * that make the fixture worth cross-checking at all.
     */
    @Test
    void theAgreedGraphContainsTheMemberSensitiveEdges() throws IOException {
        Path workspace = TreeSbomCrossCheckFixture.write(tempDir.resolve("member-sensitive"));

        String sbom = sbom(workspace);
        Set<String> edges = DependencySubmissionNormalizer.treeEdges(
                tree(workspace), DependencySubmissionNormalizer.firstPartyNodes(sbom));

        assertEquals(
                Set.of(
                        // `shared` is one node across its compile and test copies; its children are the
                        // union of the two member graphs, and never the collapsed-only `orphan` edge.
                        "org.example:shared:1.0.0:jar -> org.example:extra:2.0.0:jar",
                        "org.example:shared:1.0.0:jar -> org.example:other:4.0.0:jar",
                        // A non-default artifact type on the target side, a classifier on the source
                        // side, and a legacy bare-GAV edge canonicalized into the parser's one form.
                        "org.example:other:4.0.0:jar -> org.example:bundle:3.0.0:zip",
                        "org.example:agent:0.9.0:jar|runtime -> org.example:agent-core:0.9.0:jar",
                        "org.example:harness:5.0.0:jar -> org.example:fixtures:6.0.0:jar"),
                edges);
    }

    /** Every emitted edge is the canonical five-field form the action's single parser accepts. */
    @Test
    void everyTreeEdgeIsCanonical() throws IOException {
        Path workspace = TreeSbomCrossCheckFixture.write(tempDir.resolve("canonical"));

        String tree = tree(workspace);

        for (String edge : edges(tree)) {
            assertEquals(5, edge.split(":", -1).length, "non-canonical edge " + edge + " in\n" + tree);
            assertTrue(tree.contains("\"scope\": \"" + edge.split(":", -1)[4] + "\""), edge);
        }
    }

    /**
     * The answer to "can the action see every scope the tree emits?": each of the thirteen locked
     * scopes lands in a group that the four {@code --include-*} flags turn on, so the cross-check runs
     * over the whole tree rather than a subset.
     */
    @Test
    void everyLockedScopeIsSbomIncludable() {
        SbomScopeSelection allFlags = new SbomScopeSelection(true, true, true, true);

        for (DependencyScope scope : DependencyScope.values()) {
            assertTrue(
                    allFlags.includes(SbomScopeGroup.of(scope)),
                    "scope " + scope.lockfileName() + " is unreachable by the sbom include flags");
        }
    }

    private String tree(Path workspace) {
        CommandResult result = execute(
                "tree", "--workspace", "--format", "json", "--cwd", workspace.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        return result.stdout();
    }

    private String sbom(Path workspace) {
        CommandResult result = execute(
                "sbom",
                "--workspace",
                "--offline",
                "--include-dev",
                "--include-test",
                "--include-provided",
                "--include-tools",
                "--cwd", workspace.toString(),
                "--cache-root", tempDir.resolve("cache").toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals("", result.stderr());
        return result.stdout();
    }

    private static Set<String> edges(String treeJson) {
        return treeJson.lines()
                .filter(line -> line.contains("\"dependencies\": ["))
                .map(line -> line.substring(line.indexOf('[') + 1, line.lastIndexOf(']')))
                .flatMap(list -> java.util.stream.Stream.of(list.split(", ")))
                .filter(token -> token.length() > 1)
                .map(token -> token.replace("\"", ""))
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }
}
