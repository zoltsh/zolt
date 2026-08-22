package sh.zolt.lockfile.toml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyGraphException;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

final class ZoltLockfileDependencyRootCodecTest {
    private final ZoltLockfileReader reader = new ZoltLockfileReader();
    private final ZoltLockfileWriter writer = new ZoltLockfileWriter();

    @Test
    void writesAndReadsCanonicalMemberLaneCoordinateVariantOrder() {
        LockDependencyRoot runtime = resolved(
                "apps/worker", "com.example:z", DependencyLane.RUNTIME, DependencyScope.RUNTIME, null, true);
        LockDependencyRoot api = resolved(
                "apps/api", "com.example:z", DependencyLane.API, DependencyScope.COMPILE, null, false);
        LockDependencyRoot implementation = resolved(
                "apps/api", "com.example:b", DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE, null, false);
        LockArtifactVariant tests = new LockArtifactVariant("jar", Optional.of("tests"));
        LockDependencyRoot classified = resolved(
                "apps/api", "com.example:a", DependencyLane.TEST, DependencyScope.TEST, tests, false);
        LockDependencyRoot publishOnly = new LockDependencyRoot(
                "apps/api",
                packageId("com.example:published"),
                "4.0.0",
                null,
                DependencyLane.PROVIDED,
                Optional.empty(),
                false,
                true);
        ZoltLockfile lockfile = lockfile(
                List.of(runtime, classified, publishOnly, api, implementation),
                List.of(
                        lockPackage(runtime),
                        lockPackage(classified),
                        lockPackage(api),
                        lockPackage(implementation)));

        String output = writer.write(lockfile);
        ZoltLockfile parsed = reader.read(output);

        assertEquals("""
                version = 7

                [[dependencyRoot]]
                member = "apps/api"
                id = "com.example:b"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "apps/api"
                id = "com.example:z"
                version = "1.0.0"
                lane = "api"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "apps/api"
                id = "com.example:published"
                version = "4.0.0"
                lane = "provided"
                publishOnly = true

                [[dependencyRoot]]
                member = "apps/api"
                id = "com.example:a"
                version = "1.0.0"
                variant = "jar|tests"
                lane = "test"
                resolvedScope = "test"

                [[dependencyRoot]]
                member = "apps/worker"
                id = "com.example:z"
                version = "1.0.0"
                lane = "runtime"
                resolvedScope = "runtime"
                optional = true

                """, output.substring(0, output.indexOf("[[package]]")));
        assertTrue(output.contains("variant = \"jar|tests\""));
        assertTrue(output.contains("optional = true"));
        assertTrue(output.contains("publishOnly = true"));
        assertEquals(5, parsed.dependencyRoots().size());
        assertEquals(output, writer.write(parsed));
    }

    @Test
    void rejectsMissingAndUnknownLanes() {
        assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:lib"
                version = "1.0.0"
                resolvedScope = "compile"
                """));

        LockfileReadException unknown = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:lib"
                version = "1.0.0"
                lane = "compile"
                resolvedScope = "compile"
                """));
        assertTrue(unknown.getMessage().contains("Invalid dependencyRoot lane `compile`"));

        LockfileReadException unknownScope = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:lib"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "implementation"
                """));
        assertTrue(unknownScope.getMessage().contains(
                "Invalid dependencyRoot resolvedScope `implementation`"));
    }

    @Test
    void rejectsInvalidScopePresenceAndMissingSelectedPackageThroughReader() {
        LockfileReadException missingScope = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:lib"
                version = "1.0.0"
                lane = "implementation"
                """));
        assertTrue(missingScope.getMessage().contains("resolved lock dependency root requires a resolved scope"));

        LockfileReadException publishScope = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:lib"
                version = "1.0.0"
                lane = "runtime"
                resolvedScope = "runtime"
                publishOnly = true
                """));
        assertTrue(publishScope.getMessage().contains("publish-only lock dependency root must not have a resolved scope"));

        LockfileReadException missingPackage = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:lib"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"
                """));
        assertTrue(missingPackage.getMessage().contains("selects missing package `com.example:lib:1.0.0:jar:compile`"));
    }

    @Test
    void rejectsInvalidCoordinateAndVariant() {
        LockfileReadException coordinate = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "bad id:lib"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"
                """));
        assertTrue(coordinate.getMessage().contains("Invalid dependency coordinate `bad id:lib`"));

        LockfileReadException variant = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:lib"
                version = "1.0.0"
                variant = ""
                lane = "implementation"
                resolvedScope = "compile"
                """));
        assertTrue(variant.getMessage().contains("Missing required string field `variant`"));

        LockfileReadException nonCanonicalVariant = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:lib"
                version = "1.0.0"
                variant = "jar|tests|extra"
                lane = "implementation"
                resolvedScope = "compile"
                """));
        assertTrue(nonCanonicalVariant.getMessage().contains("not a canonical artifact variant key"));

        LockfileReadException normalizedVariant = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:lib"
                version = "1.0.0"
                variant = "|tests"
                lane = "implementation"
                resolvedScope = "compile"
                """));
        assertTrue(normalizedVariant.getMessage().contains(
                "dependencyRoot variant `|tests` is not a canonical artifact variant key"));
    }

    @Test
    void roundTripsAuthoredLaneIndependentlyFromResolvedScope() {
        ZoltLockfile parsed = reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:lib"
                version = "1.0.0"
                lane = "api"
                resolvedScope = "runtime"

                [[package]]
                id = "com.example:lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "runtime"
                direct = true
                dependencies = []
                """);

        LockDependencyRoot root = parsed.dependencyRoots().getFirst();
        assertEquals(DependencyLane.API, root.lane());
        assertEquals(DependencyScope.RUNTIME, root.resolvedScope().orElseThrow());
        assertEquals(parsed, reader.read(writer.write(parsed)));
    }

    @Test
    void rejectsDirectOrdinaryPackagesWithoutExactRootsAtBothWireBoundaries() {
        String rootless = """
                version = 7

                [[package]]
                id = "com.example:lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                dependencies = []
                """;

        LockfileReadException readFailure = assertThrows(
                LockfileReadException.class,
                () -> reader.read(rootless));
        assertTrue(readFailure.getMessage().contains(
                "Direct package `com.example:lib:1.0.0:jar:compile` has no exact dependencyRoot"));

        LockPackage direct = new LockPackage(
                packageId("com.example:lib"),
                "1.0.0",
                "maven-central",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
        LockfileWriteException writeFailure = assertThrows(
                LockfileWriteException.class,
                () -> writer.write(lockfile(List.of(), List.of(direct))));
        assertTrue(writeFailure.getMessage().contains(
                "Direct package `com.example:lib:1.0.0:jar:compile` has no exact dependencyRoot"));
    }

    @Test
    void allowsInjectedToolPackagesWithoutAuthoredRoots() {
        LockPackage tool = new LockPackage(
                packageId("com.example:coverage-tool"),
                "1.0.0",
                "maven-central",
                DependencyScope.TOOL_COVERAGE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
        ZoltLockfile lockfile = lockfile(List.of(), List.of(tool));

        assertEquals(lockfile, reader.read(writer.write(lockfile)));
    }

    /**
     * Design §4.5 attributes every locked package to the members that declared it, so a root selects a
     * package only for a member it is attributed to. A root naming member B for a dependency member A
     * declared is misattributed evidence: the lock model refuses to hold the pair, and the reader
     * refuses the persisted bytes. Root completeness now applies that same shared selection rule
     * rather than a coordinate-only copy of it, so the two guards cannot drift apart.
     */
    @Test
    void rejectsARootAttributedToAMemberThatDidNotDeclareTheDependency() {
        LockDependencyRoot rootNamingB = resolved(
                "modules/b", "com.example:lib", DependencyLane.API, DependencyScope.COMPILE, null, false);
        LockPackage declaredByA = attributedTo(lockPackage(rootNamingB), "modules/a");

        LockDependencyGraphException rejected = assertThrows(
                LockDependencyGraphException.class,
                () -> lockfile(List.of(rootNamingB), List.of(declaredByA)));
        assertTrue(
                rejected.getMessage().contains("selects missing package `com.example:lib:1.0.0:jar:compile`"),
                rejected.getMessage());

        LockfileReadException readFailure = assertThrows(LockfileReadException.class, () -> reader.read("""
                version = 7

                [[dependencyRoot]]
                member = "modules/b"
                id = "com.example:lib"
                version = "1.0.0"
                lane = "api"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:lib"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                members = ["modules/a"]
                dependencies = []
                """));
        assertTrue(
                readFailure.getMessage().contains("selects missing package `com.example:lib:1.0.0:jar:compile`"),
                readFailure.getMessage());
    }

    private static LockPackage attributedTo(LockPackage lockPackage, String member) {
        return new LockPackage(
                lockPackage.packageId(),
                lockPackage.version(),
                lockPackage.source(),
                lockPackage.scope(),
                lockPackage.direct(),
                lockPackage.jar(),
                lockPackage.pom(),
                lockPackage.jarSha256(),
                lockPackage.pomSha256(),
                lockPackage.artifact(),
                lockPackage.artifactType(),
                lockPackage.artifactSha256(),
                lockPackage.workspace(),
                lockPackage.workspaceOutput(),
                lockPackage.dependencies(),
                List.of(member),
                lockPackage.exportedBy(),
                lockPackage.policies(),
                lockPackage.toolGroups());
    }

    private static ZoltLockfile lockfile(
            List<LockDependencyRoot> roots,
            List<LockPackage> packages) {
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

    private static LockDependencyRoot resolved(
            String member,
            String id,
            DependencyLane lane,
            DependencyScope scope,
            LockArtifactVariant variant,
            boolean optional) {
        return new LockDependencyRoot(
                member,
                packageId(id),
                "1.0.0",
                variant,
                lane,
                Optional.of(scope),
                optional,
                false);
    }

    private static LockPackage lockPackage(LockDependencyRoot root) {
        String classifier = root.variant().classifier().orElse(null);
        String artifact = root.packageId().artifactId() + "-" + root.version()
                + (classifier == null ? "" : "-" + classifier) + "." + root.variant().extension();
        return new LockPackage(
                root.packageId(),
                root.version(),
                "maven-central",
                root.resolvedScope().orElseThrow(),
                true,
                Optional.of(artifact),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(root.member()));
    }

    private static PackageId packageId(String value) {
        String[] parts = value.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }
}
