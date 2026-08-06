package sh.zolt.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The machine-contract guarantees the schema-3 projection owes its consumers: every emitted edge names
 * a listed occurrence in one canonical spelling, children are sourced the way the workspace SBOM
 * sources them, and a lock that cannot be projected unambiguously fails closed with an actionable
 * message instead of emitting a document a consumer cannot key.
 */
final class WorkspaceTreeProjectionContractTest extends WorkspaceTreeTestSupport {
    private final WorkspaceDependencyJsonFormatter jsonFormatter = new WorkspaceDependencyJsonFormatter();
    private final WorkspaceDependencyTreeFormatter treeFormatter = new WorkspaceDependencyTreeFormatter();

    @Test
    void canonicalizesALegacyBareGavEdgeToTheOccurrenceItNames() {
        ZoltLockfile lockfile = legacyEdgeLockfile();

        String output = jsonFormatter.tree(WORKSPACE_NAME, JSON_MEMBERS, lockfile);

        assertTrue(edges(output).contains("org.example:extra:2.0.0:jar:compile"), output);
        assertTrue(
                edges(output).stream().noneMatch(edge -> edge.equals("org.example:extra:2.0.0")),
                output);
    }

    @Test
    void emitsOnlyEdgesThatNameAListedOccurrenceEvenFromALegacyLock() {
        ZoltLockfile lockfile = legacyEdgeLockfile();

        String output = jsonFormatter.tree(WORKSPACE_NAME, JSON_MEMBERS, lockfile);

        for (String edge : edges(output)) {
            assertTrue(identities(lockfile).contains(edge), "dangling edge " + edge + " in\n" + output);
        }
    }

    /**
     * A legacy edge that resolves to a classified or non-default-type occurrence must gain the variant
     * as well as the scope, so the consumer's one edge parser reads the full identity.
     */
    @Test
    void canonicalizesAVariantQualifiedLegacyEdgeWithItsScope() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(
                        external(
                                "org.example",
                                "shared",
                                "1.0.0",
                                DependencyScope.RUNTIME,
                                true,
                                List.of("org.example:bundle:3.0.0:zip"),
                                List.of("apps/api")),
                        typedBundle()),
                List.of());

        String output = jsonFormatter.tree(WORKSPACE_NAME, JSON_MEMBERS, lockfile);

        assertTrue(
                output.contains("\"dependencies\": [\"org.example:bundle:3.0.0:zip:runtime\"]"),
                output);
    }

    /**
     * The workspace SBOM sources an attributed package's children only from its per-member graphs, so
     * a collapsed list that is strictly larger must not leak into the tree — otherwise the two views
     * disagree about the same lock and a cross-checking consumer aborts.
     */
    @Test
    void sourcesChildrenFromTheMemberGraphsWhenTheCollapsedListIsLarger() {
        ZoltLockfile lockfile = collapsedSupersetLockfile();

        String output = jsonFormatter.tree(WORKSPACE_NAME, JSON_MEMBERS, lockfile);

        assertEquals(List.of(), edges(output), output);
    }

    @Test
    void refusesALockThatListsOneOccurrenceTwice() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(extra(), extra()),
                List.of());

        LockDependencyGraphException json = assertThrows(
                LockDependencyGraphException.class,
                () -> jsonFormatter.tree(WORKSPACE_NAME, JSON_MEMBERS, lockfile));
        LockDependencyGraphException text = assertThrows(
                LockDependencyGraphException.class,
                () -> treeFormatter.format(WORKSPACE_NAME, MEMBERS, lockfile));

        assertTrue(json.getMessage().contains("org.example:extra:2.0.0:jar:compile"), json.getMessage());
        assertTrue(json.getMessage().contains("more than once"), json.getMessage());
        assertTrue(json.getMessage().contains("zolt resolve --workspace"), json.getMessage());
        assertEquals(json.getMessage(), text.getMessage());
    }

    @Test
    void refusesALockAttributingAPackageToAnUndeclaredMember() {
        ZoltLockfile lockfile = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(external(
                        "org.example",
                        "extra",
                        "2.0.0",
                        DependencyScope.COMPILE,
                        false,
                        List.of(),
                        List.of("apps/removed"))),
                List.of());

        LockDependencyGraphException json = assertThrows(
                LockDependencyGraphException.class,
                () -> jsonFormatter.tree(WORKSPACE_NAME, JSON_MEMBERS, lockfile));
        LockDependencyGraphException text = assertThrows(
                LockDependencyGraphException.class,
                () -> treeFormatter.format(WORKSPACE_NAME, MEMBERS, lockfile));

        assertTrue(json.getMessage().contains("apps/removed"), json.getMessage());
        assertTrue(json.getMessage().contains("does not declare as members"), json.getMessage());
        assertTrue(json.getMessage().contains("zolt resolve --workspace"), json.getMessage());
        assertEquals(json.getMessage(), text.getMessage());
    }

    @Test
    void canonicalizationIsByteStableAcrossRuns() {
        ZoltLockfile lockfile = legacyEdgeLockfile();

        String first = jsonFormatter.tree(WORKSPACE_NAME, JSON_MEMBERS, lockfile);
        String second = new WorkspaceDependencyJsonFormatter()
                .tree(WORKSPACE_NAME, JSON_MEMBERS.reversed(), lockfile);

        assertEquals(first, second);
    }
}
