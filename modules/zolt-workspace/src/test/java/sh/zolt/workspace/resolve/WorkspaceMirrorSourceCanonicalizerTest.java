package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;

final class WorkspaceMirrorSourceCanonicalizerTest {
    private static final PackageId LIB = new PackageId("com.example", "lib");

    @Test
    void choosesOneDeterministicSourceForByteIdenticalMirrorsAcrossScopes() {
        List<LockPackage> canonical = WorkspaceMirrorSourceCanonicalizer.canonicalize(List.of(
                external("corp-mirror", DependencyScope.COMPILE, "jar-sha", "pom-sha"),
                external("central", DependencyScope.RUNTIME, "jar-sha", "pom-sha")));

        assertEquals(List.of("central", "central"), canonical.stream()
                .map(LockPackage::source)
                .toList());
        assertEquals(List.of(DependencyScope.COMPILE, DependencyScope.RUNTIME), canonical.stream()
                .map(LockPackage::scope)
                .toList());
    }

    @Test
    void doesNotCanonicalizeDifferentBytes() {
        List<LockPackage> unchanged = WorkspaceMirrorSourceCanonicalizer.canonicalize(List.of(
                external("corp-mirror", DependencyScope.COMPILE, "corp-sha", "pom-sha"),
                external("central", DependencyScope.RUNTIME, "central-sha", "pom-sha")));

        assertEquals(List.of("corp-mirror", "central"), unchanged.stream()
                .map(LockPackage::source)
                .toList());
    }

    private static LockPackage external(
            String source,
            DependencyScope scope,
            String jarSha,
            String pomSha) {
        String base = "com/example/lib/1.0.0/lib-1.0.0";
        return new LockPackage(
                LIB,
                "1.0.0",
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
