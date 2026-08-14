package sh.zolt.lockfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LockDependencyIndexTest {
    private static final PackageId NETTY = new PackageId("io.netty", "netty");

    @Test
    void qualifiedEdgeResolvesToTheExactVariant() {
        LockPackage linux = jarPackage(NETTY, "4.1.90.Final",
                "io/netty/netty/4.1.90.Final/netty-4.1.90.Final-linux-x86_64.jar");
        LockPackage osx = jarPackage(NETTY, "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final-osx-aarch_64.jar");
        LockDependencyIndex index = new LockDependencyIndex(List.of(linux, osx));

        assertEquals(linux, index.resolve("io.netty:netty:4.1.90.Final:jar|linux-x86_64").orElseThrow());
        assertEquals(osx, index.resolve("io.netty:netty:4.1.100.Final:jar|osx-aarch_64").orElseThrow());
    }

    @Test
    void bareEdgeResolvesToDefaultVariantWhenPresent() {
        LockPackage plain = jarPackage(NETTY, "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final.jar");
        LockPackage classified = jarPackage(NETTY, "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final-linux-x86_64.jar");
        LockDependencyIndex index = new LockDependencyIndex(List.of(classified, plain));

        assertEquals(plain, index.resolve("io.netty:netty:4.1.100.Final").orElseThrow());
    }

    @Test
    void bareEdgeResolvesToSoleVariantEvenWhenNonDefault() {
        // A lock written before variant qualifiers stores a bare edge even to a classified sole artifact.
        LockPackage classified = jarPackage(NETTY, "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final-linux-x86_64.jar");
        LockDependencyIndex index = new LockDependencyIndex(List.of(classified));

        assertEquals(classified, index.resolve("io.netty:netty:4.1.100.Final").orElseThrow());
    }

    @Test
    void bareEdgeIsUnresolvedWhenSeveralVariantsAndNoDefault() {
        LockPackage linux = jarPackage(NETTY, "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final-linux-x86_64.jar");
        LockPackage osx = jarPackage(NETTY, "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final-osx-aarch_64.jar");
        LockDependencyIndex index = new LockDependencyIndex(List.of(linux, osx));

        assertTrue(index.resolve("io.netty:netty:4.1.100.Final").isEmpty());
    }

    @Test
    void scopeQualifiedEdgeSelectsTheExactScopeCopy() {
        LockPackage compile = jarPackage(
                NETTY,
                "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final.jar",
                DependencyScope.COMPILE);
        LockPackage runtime = jarPackage(
                NETTY,
                "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final.jar",
                DependencyScope.RUNTIME);
        LockDependencyIndex index = new LockDependencyIndex(List.of(compile, runtime));

        assertEquals(
                compile,
                index.resolve("io.netty:netty:4.1.100.Final:jar:compile").orElseThrow());
        assertEquals(
                runtime,
                index.resolve("io.netty:netty:4.1.100.Final:jar:runtime").orElseThrow());
        assertTrue(index.resolve("io.netty:netty:4.1.100.Final").isEmpty());
    }

    @Test
    void graphResolutionRefusesAmbiguousLegacyScopeCopies() {
        LockPackage compile = jarPackage(
                NETTY,
                "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final.jar",
                DependencyScope.COMPILE);
        LockPackage runtime = jarPackage(
                NETTY,
                "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final.jar",
                DependencyScope.RUNTIME);
        LockDependencyIndex index = new LockDependencyIndex(List.of(compile, runtime));

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> index.resolveGraphEdge(
                        "io.netty:netty:4.1.100.Final",
                        "zolt resolve"));

        assertTrue(exception.getMessage().contains("ambiguous"));
        assertTrue(exception.getMessage().contains("zolt resolve"));
        assertTrue(
                exception.getMessage().contains("version " + ZoltLockfile.CURRENT_VERSION),
                exception.getMessage());
    }

    @Test
    void graphResolutionRefusesDanglingScopeQualifiedEdge() {
        LockDependencyIndex index = new LockDependencyIndex(List.of());

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> index.resolveGraphEdge(
                        "io.netty:netty:4.1.100.Final:jar:compile",
                        "zolt resolve"));

        assertTrue(exception.getMessage().toLowerCase().contains("dangling"));
        assertTrue(exception.getMessage().contains("jar:compile"));
        assertTrue(exception.getMessage().contains("zolt resolve"));
    }

    @Test
    void refusesDuplicateExactTargetsFromDifferentSources() {
        LockPackage released = jarPackage(
                NETTY,
                "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final.jar",
                DependencyScope.COMPILE);
        LockPackage workspace = new LockPackage(
                NETTY,
                "4.1.100.Final",
                "workspace",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("modules/netty"),
                Optional.of("target/classes"),
                List.of(),
                List.of("apps/api"),
                List.of(),
                List.of(),
                List.of());

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> new LockDependencyIndex(List.of(workspace, released)));

        assertTrue(exception.getMessage().contains(
                "io.netty:netty:4.1.100.Final:jar:compile"));
        assertTrue(exception.getMessage().contains("multiple locked package sources"));
    }

    @Test
    void refusesDifferentLocalAndReleasedTargetsAcrossScopes() {
        LockPackage released = jarPackage(
                NETTY,
                "4.1.100.Final",
                "io/netty/netty/4.1.100.Final/netty-4.1.100.Final.jar",
                DependencyScope.RUNTIME);
        LockPackage workspace = new LockPackage(
                NETTY,
                "4.1.100.Final",
                "workspace",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("modules/netty"),
                Optional.of("target/classes"),
                List.of(),
                List.of("apps/api"),
                List.of(),
                List.of(),
                List.of());

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> new LockDependencyIndex(List.of(workspace, released)));

        assertTrue(exception.getMessage().contains(
                "io.netty:netty:4.1.100.Final:jar"));
        assertTrue(exception.getMessage().contains("across dependency scopes"));
        assertTrue(exception.getMessage().contains("multiple locked package sources"));
    }

    @Test
    void acceptsByteIdenticalRepositoryMirrorsAcrossScopes() {
        LockPackage compile = mirrorPackage(
                "corp-mirror",
                DependencyScope.COMPILE,
                "same-jar-sha",
                "same-pom-sha");
        LockPackage runtime = mirrorPackage(
                "central",
                DependencyScope.RUNTIME,
                "same-jar-sha",
                "same-pom-sha");

        LockDependencyIndex index = new LockDependencyIndex(List.of(compile, runtime));

        assertEquals(
                compile,
                index.resolve("io.netty:netty:4.1.100.Final:jar:compile").orElseThrow());
        assertEquals(
                runtime,
                index.resolve("io.netty:netty:4.1.100.Final:jar:runtime").orElseThrow());
    }

    @Test
    void refusesRepositoryMirrorsWhenVerifiedBytesDiffer() {
        LockPackage compile = mirrorPackage(
                "corp-mirror",
                DependencyScope.COMPILE,
                "corp-jar-sha",
                "same-pom-sha");
        LockPackage runtime = mirrorPackage(
                "central",
                DependencyScope.RUNTIME,
                "central-jar-sha",
                "same-pom-sha");

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> new LockDependencyIndex(List.of(compile, runtime)));

        assertTrue(exception.getMessage().contains("across dependency scopes"));
    }

    private static LockPackage jarPackage(PackageId packageId, String version, String jarPath) {
        return jarPackage(packageId, version, jarPath, DependencyScope.RUNTIME);
    }

    private static LockPackage jarPackage(
            PackageId packageId,
            String version,
            String jarPath,
            DependencyScope scope) {
        return new LockPackage(
                packageId,
                version,
                "maven-central",
                scope,
                false,
                Optional.of(jarPath),
                Optional.of(jarPath.substring(0, jarPath.lastIndexOf('.')) + ".pom"),
                Optional.of("jar-sha"),
                Optional.of("pom-sha"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static LockPackage mirrorPackage(
            String source,
            DependencyScope scope,
            String jarSha,
            String pomSha) {
        String base = "io/netty/netty/4.1.100.Final/netty-4.1.100.Final";
        return new LockPackage(
                NETTY,
                "4.1.100.Final",
                source,
                scope,
                true,
                Optional.of(base + ".jar"),
                Optional.of(base + ".pom"),
                Optional.of(jarSha),
                Optional.of(pomSha),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of("apps/api"),
                List.of(),
                List.of(),
                List.of());
    }
}
