package sh.zolt.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class PublishInterMemberGuardTest {
    @Test
    void reportsInterMemberSiblingsAbsentFromThePublishSet() {
        LockPackage sibling = workspacePackage("com.acme", "acme-core", "1.0.0", DependencyScope.RUNTIME, false);
        LockPackage external = external("org.slf4j", "slf4j-api", "2.0.13");
        LockPackage testSibling = workspacePackage("com.acme", "test-support", "1.0.0", DependencyScope.TEST, true);
        ZoltLockfile memberLock = lockfile(
                List.of(
                        sibling,
                        external,
                        testSibling),
                List.of(
                        root(sibling, DependencyLane.IMPLEMENTATION),
                        root(external, DependencyLane.API),
                        root(testSibling, DependencyLane.TEST)));

        List<String> missing = PublishInterMemberGuard.missingSiblings(memberLock, Set.of("com.acme:acme-http"));

        // acme-core is an inter-member dependency and is not in the publish set; slf4j is external and ignored.
        assertEquals(List.of("com.acme:acme-core"), missing);
    }

    @Test
    void reportsNothingWhenEverySiblingIsInThePublishSet() {
        LockPackage sibling = workspacePackage("com.acme", "acme-core", "1.0.0", DependencyScope.COMPILE, true);
        ZoltLockfile memberLock = lockfile(List.of(sibling), List.of(root(sibling, DependencyLane.API)));

        List<String> missing = PublishInterMemberGuard.missingSiblings(
                memberLock, Set.of("com.acme:acme-http", "com.acme:acme-core"));

        assertTrue(missing.isEmpty());
    }

    @Test
    void rejectsPreV7LocksInsteadOfInferringFromDirectPackages() {
        PublishException exception = assertThrows(
                PublishException.class,
                () -> PublishInterMemberGuard.missingSiblings(
                        new ZoltLockfile(6, List.of(), List.of()),
                        Set.of()));

        assertTrue(exception.getMessage().contains("require zolt.lock version 7"));
    }

    private static LockPackage external(String group, String artifact, String version) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "https://repo.maven.apache.org/maven2",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    private static LockPackage workspacePackage(
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            boolean direct) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "workspace",
                scope,
                direct,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(artifact),
                Optional.of("target/classes"),
                List.of());
    }

    private static LockDependencyRoot root(LockPackage lockPackage, DependencyLane lane) {
        return new LockDependencyRoot(
                ".",
                lockPackage.packageId(),
                lockPackage.version(),
                null,
                lane,
                Optional.of(lockPackage.scope()),
                false,
                false);
    }

    private static ZoltLockfile lockfile(
            List<LockPackage> packages,
            List<LockDependencyRoot> roots) {
        return new ZoltLockfile(
                7, Optional.empty(), Optional.empty(), List.of(), packages, List.of(), List.of(), List.of(), roots);
    }
}
