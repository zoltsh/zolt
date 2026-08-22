package sh.zolt.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.PackageId;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DependencyTreeFormatterTest extends DependencyTreeTestSupport {
    private final DependencyTreeFormatter formatter = new DependencyTreeFormatter();

    @Test
    void formatsDirectAndTransitiveDependenciesDeterministically() {
        ZoltLockfile lockfile = lockfile(
                List.of(
                        lockPackage("org.slf4j", "slf4j-api", "2.0.16", false, List.of()),
                        lockPackage("com.google.guava", "guava", "33.4.0-jre", true, List.of(
                                "org.slf4j:slf4j-api:2.0.16",
                                "com.google.guava:failureaccess:1.0.2")),
                        lockPackage("com.google.guava", "failureaccess", "1.0.2", false, List.of())),
                List.of());

        String output = formatter.format(config(), lockfile);

        assertEquals("""
                com.example:demo:0.1.0
                \\- com.google.guava:guava:33.4.0-jre (lane: implementation; resolved scope: compile)
                   +- com.google.guava:failureaccess:1.0.2
                   \\- org.slf4j:slf4j-api:2.0.16
                """, output);
    }

    @Test
    void resolvesVariantQualifiedEdgesToTheirOwnVersionsWithoutMislabeling() {
        // app depends on two classified netty variants at DIFFERENT versions via variant-qualified edges.
        // Each edge must resolve to its OWN variant, so the tree shows the correct distinct versions.
        ZoltLockfile lockfile = lockfile(
                List.of(
                        lockPackage("com.example", "app", "1.0.0", true, List.of(
                                "io.netty:netty:4.1.90.Final:jar|linux-x86_64",
                                "io.netty:netty:4.1.100.Final:jar|osx-aarch_64")),
                        classified("io.netty", "netty", "4.1.90.Final", "linux-x86_64"),
                        classified("io.netty", "netty", "4.1.100.Final", "osx-aarch_64")),
                List.of());

        String output = formatter.format(config(), lockfile);

        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:app:1.0.0 (lane: implementation; resolved scope: compile)
                   +- io.netty:netty:4.1.100.Final:jar|osx-aarch_64
                   \\- io.netty:netty:4.1.90.Final:jar|linux-x86_64
                """, output);
    }

    @Test
    void keepsConflictAnnotationsInTheirQualifiedVariantLanes() {
        ZoltLockfile lockfile = lockfile(
                List.of(
                        lockPackage("com.example", "app", "1.0.0", true, List.of(
                                "io.netty:netty:4.1.90.Final:jar|linux-x86_64",
                                "io.netty:netty:4.1.100.Final:jar|osx-aarch_64")),
                        classified("io.netty", "netty", "4.1.90.Final", "linux-x86_64"),
                        classified("io.netty", "netty", "4.1.100.Final", "osx-aarch_64")),
                List.of(
                        new LockConflict(
                                new PackageId("io.netty", "netty"),
                                "4.1.90.Final",
                                List.of("4.1.80.Final", "4.1.90.Final"),
                                ConflictSelectionReason.NEWEST_VERSION,
                                java.util.Optional.empty(),
                                java.util.Optional.of(new sh.zolt.lockfile.LockArtifactVariant(
                                        "jar", java.util.Optional.of("linux-x86_64")))),
                        new LockConflict(
                                new PackageId("io.netty", "netty"),
                                "4.1.100.Final",
                                List.of("4.1.99.Final", "4.1.100.Final"),
                                ConflictSelectionReason.DIRECT_DEPENDENCY,
                                java.util.Optional.empty(),
                                java.util.Optional.of(new sh.zolt.lockfile.LockArtifactVariant(
                                        "jar", java.util.Optional.of("osx-aarch_64"))))));

        String output = formatter.format(config(), lockfile);

        assertEquals(1, output.split("selected 4.1.90.Final", -1).length - 1);
        assertEquals(1, output.split("selected 4.1.100.Final", -1).length - 1);
    }

    @Test
    void marksPackagesWithSelectedConflictVersions() {
        ZoltLockfile lockfile = lockfile(
                List.of(lockPackage("org.slf4j", "slf4j-api", "2.0.16", true, List.of())),
                List.of(new LockConflict(
                        new PackageId("org.slf4j", "slf4j-api"),
                        "2.0.16",
                        List.of("1.7.36", "2.0.16"),
                        ConflictSelectionReason.DIRECT_DEPENDENCY)));

        String output = formatter.format(config(), lockfile);

        assertEquals("""
                com.example:demo:0.1.0
                \\- org.slf4j:slf4j-api:2.0.16 (lane: implementation; resolved scope: compile) (conflict: selected 2.0.16; requested 1.7.36, 2.0.16; direct dependency wins)
                """, output);
    }

    @Test
    void showsPackagePolicies() {
        ZoltLockfile lockfile = lockfile(
                List.of(lockPackage(
                        "com.example",
                        "app",
                        "1.0.0",
                        true,
                        List.of(),
                        List.of("managed-version: com.example:app -> 1.0.0 from com.example:platform:1.0.0"))),
                List.of());

        String output = formatter.format(config(), lockfile);

        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:app:1.0.0 (lane: implementation; resolved scope: compile) (policy: managed-version: com.example:app -> 1.0.0 from com.example:platform:1.0.0)
                """, output);
    }

    @Test
    void showsExcludedPolicyEffects() {
        ZoltLockfile lockfile = lockfile(
                List.of(lockPackage("com.example", "app", "1.0.0", true, List.of())),
                List.of(),
                List.of(policyEffect()));

        String output = formatter.format(config(), lockfile);

        assertEquals("""
                com.example:demo:0.1.0
                \\- com.example:app:1.0.0 (lane: implementation; resolved scope: compile)
                Policy effects
                - global-exclusion commons-logging:commons-logging:1.2 from com.example:app:1.0.0: [dependencies.policy].deny commons-logging:commons-logging (Use jcl-over-slf4j)
                """, output);
    }
}
