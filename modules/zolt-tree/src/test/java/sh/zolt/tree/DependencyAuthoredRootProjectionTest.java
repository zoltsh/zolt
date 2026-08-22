package sh.zolt.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

final class DependencyAuthoredRootProjectionTest extends DependencyTreeTestSupport {
    private final DependencyTreeFormatter treeFormatter = new DependencyTreeFormatter();
    private final DependencyWhyFormatter whyFormatter = new DependencyWhyFormatter();
    private final DependencyJsonFormatter jsonFormatter = new DependencyJsonFormatter();

    @Test
    void rootsAndDirectFlagsComeFromAuthoredRootsRatherThanPackageFlags() {
        LockPackage selected = lockPackage("com.example", "selected", "1.0.0", false, List.of());
        LockPackage staleDirect = lockPackage("com.example", "stale", "1.0.0", true, List.of());
        ZoltLockfile lockfile = authoredLockfile(
                List.of(selected, staleDirect),
                List.of(root(".", selected, DependencyLane.API)));

        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:selected:1.0.0 (lane: api; resolved scope: compile)
                """, treeFormatter.format(config(), lockfile));

        String json = jsonFormatter.tree(config(), lockfile);
        assertTrue(json.contains("\"roots\": [\"com.example:selected:1.0.0\"]"), json);
        assertTrue(json.contains("""
                      "id": "com.example:selected",
                      "version": "1.0.0",
                      "coordinate": "com.example:selected:1.0.0",
                      "scope": "compile",
                      "direct": true,
                """), json);
        assertTrue(json.contains("""
                      "id": "com.example:stale",
                      "version": "1.0.0",
                      "coordinate": "com.example:stale:1.0.0",
                      "scope": "compile",
                      "direct": false,
                """), json);
    }

    @Test
    void keepsApiAndImplementationSeparateDespiteTheirSharedResolvedScope() {
        LockPackage api = lockPackage("com.example", "api", "1.0.0", false, List.of());
        LockPackage implementation = lockPackage("com.example", "implementation", "1.0.0", false, List.of());
        ZoltLockfile lockfile = authoredLockfile(
                List.of(api, implementation),
                List.of(
                        root(".", api, DependencyLane.API),
                        root(".", implementation, DependencyLane.IMPLEMENTATION)));

        assertEquals("""
                com.example:demo:0.1.0
                +- com.example:implementation:1.0.0 (lane: implementation; resolved scope: compile)
                \\- com.example:api:1.0.0 (lane: api; resolved scope: compile)
                """, treeFormatter.format(config(), lockfile));
    }

    @Test
    void publishOnlyRootIsTextualButNotAForgedJsonGraphRoot() {
        PackageId packageId = new PackageId("com.example", "published-api");
        LockDependencyRoot publishOnly = new LockDependencyRoot(
                ".",
                packageId,
                "1.0.0",
                LockArtifactVariant.defaultVariant(),
                DependencyLane.API,
                Optional.empty(),
                false,
                true);
        ZoltLockfile lockfile = authoredLockfile(List.of(), List.of(publishOnly));

        String expected = """
                com.example:demo:0.1.0
                \\- com.example:published-api:1.0.0 (lane: api; publish only)
                """;
        assertEquals(expected, treeFormatter.format(config(), lockfile));
        assertEquals(expected, whyFormatter.format(config(), lockfile, packageId));
        assertTrue(jsonFormatter.tree(config(), lockfile).contains("\"roots\": []"));

        DependencyWhyException exception = assertThrows(
                DependencyWhyException.class,
                () -> jsonFormatter.why(config(), lockfile, packageId));
        assertTrue(exception.getMessage().contains("why JSON schema 1 cannot represent"), exception.getMessage());
    }

    @Test
    void ordersVariantsByTheirCanonicalWireKeys() {
        PackageId packageId = new PackageId("com.example", "variants");
        LockDependencyRoot classifiedJar = publishOnly(
                packageId,
                new LockArtifactVariant("jar", Optional.of("tests")));
        LockDependencyRoot jarsType = publishOnly(
                packageId,
                new LockArtifactVariant("jars", Optional.empty()));
        ZoltLockfile lockfile = authoredLockfile(
                List.of(),
                List.of(classifiedJar, jarsType));

        assertEquals("""
                com.example:demo:0.1.0
                +- com.example:variants:1.0.0:jars (lane: api; publish only)
                \\- com.example:variants:1.0.0:jar|tests (lane: api; publish only)
                """, treeFormatter.format(config(), lockfile));
    }

    @Test
    void refusesToInferRootsOrLanesFromPreVersionSevenDirectFlags() {
        ZoltLockfile lockfile = new ZoltLockfile(
                6,
                List.of(lockPackage("com.example", "legacy", "1.0.0", true, List.of())),
                List.of());

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> treeFormatter.format(config(), lockfile));

        assertTrue(exception.getMessage().contains("version 6 cannot prove authored dependency lanes"));
        assertTrue(exception.getMessage().contains("zolt resolve"));
    }

    private static ZoltLockfile authoredLockfile(
            List<LockPackage> packages,
            List<LockDependencyRoot> roots) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                packages,
                List.of(),
                List.of(),
                List.of(),
                roots);
    }

    private static LockDependencyRoot publishOnly(
            PackageId packageId,
            LockArtifactVariant variant) {
        return new LockDependencyRoot(
                ".",
                packageId,
                "1.0.0",
                variant,
                DependencyLane.API,
                Optional.empty(),
                false,
                true);
    }
}
