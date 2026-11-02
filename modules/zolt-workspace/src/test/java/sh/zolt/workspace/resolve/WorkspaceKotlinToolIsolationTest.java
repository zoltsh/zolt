package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveResult;

final class WorkspaceKotlinToolIsolationTest extends WorkspaceResolveServiceTestSupport {
    private static final PackageId KOTLIN_COMPILER =
            new PackageId("org.jetbrains.kotlin", "kotlin-compiler-embeddable");
    private static final PackageId KOTLIN_STDLIB =
            new PackageId("org.jetbrains.kotlin", "kotlin-stdlib");

    @Test
    void preservesMemberCompilerVersionsAndFingerprintsToolchainChanges() throws IOException {
        addArtifact(
                "org.jetbrains.kotlin",
                "kotlin-stdlib",
                "1.9.24",
                pom("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.24"));
        addKotlinArtifacts("2.2.0");
        addKotlinArtifacts("2.2.10");
        addKotlinArtifacts("2.2.20");
        workspace("""
                [workspace]
                name = "kotlin-tool-isolation"

                [workspace.members]
                include = ["apps/api", "apps/worker"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", memberConfig("2.2.0", true));
        member("apps/worker", "worker", memberConfig("2.2.10", false));

        ResolveResult initial = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        ZoltLockfile initialLock = lockfileReader.read(initial.lockfilePath());
        assertCompilerClosure(initialLock, "2.2.0", "apps/api");
        assertCompilerClosure(initialLock, "2.2.10", "apps/worker");
        assertTrue(packageEntry(
                initialLock, KOTLIN_STDLIB, DependencyScope.COMPILE, "1.9.24").direct());
        assertFalse(initialLock.conflicts().stream()
                .anyMatch(conflict -> conflict.packageId().equals(KOTLIN_COMPILER)
                        || conflict.packageId().equals(KOTLIN_STDLIB)));

        String initialText = Files.readString(initial.lockfilePath());
        member("apps/worker", "worker", memberConfig("2.2.20", false));
        ResolveException locked = assertThrows(
                ResolveException.class,
                () -> service.resolve(tempDir, tempDir.resolve("cache"), true, false));
        assertTrue(locked.getMessage().contains("Workspace zolt.lock is out of date"));
        assertTrue(locked.getMessage().contains("toolchain.kotlin"));
        assertEquals(initialText, Files.readString(initial.lockfilePath()));

        ResolveResult changed = service.resolve(tempDir, tempDir.resolve("cache"), false, false);
        ZoltLockfile changedLock = lockfileReader.read(changed.lockfilePath());
        assertCompilerClosure(changedLock, "2.2.0", "apps/api");
        assertCompilerClosure(changedLock, "2.2.20", "apps/worker");
        assertFalse(changedLock.packages().stream()
                .anyMatch(lockPackage -> lockPackage.scope() == DependencyScope.TOOL_KOTLIN
                        && lockPackage.version().equals("2.2.10")));
    }

    @Test
    void recognizesKotlinAsAnIsolatedCompilerScope() {
        assertTrue(WorkspaceIsolatedToolPackageSelector.isIsolatedScope(
                DependencyScope.TOOL_KOTLIN));
    }

    private void addKotlinArtifacts(String version) {
        addArtifact("org.jetbrains.kotlin", "kotlin-compiler-embeddable", version, """
                <project>
                  <groupId>org.jetbrains.kotlin</groupId>
                  <artifactId>kotlin-compiler-embeddable</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.jetbrains.kotlin</groupId>
                      <artifactId>kotlin-stdlib</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(version, version));
        addArtifact(
                "org.jetbrains.kotlin",
                "kotlin-stdlib",
                version,
                pom("org.jetbrains.kotlin", "kotlin-stdlib", version));
    }

    private static String memberConfig(String kotlinVersion, boolean applicationDependency) {
        String dependency = applicationDependency
                ? """

                  [dependencies]
                  "org.jetbrains.kotlin:kotlin-stdlib" = "1.9.24"
                  """
                : "";
        return """

                [toolchain.kotlin]
                version = "%s"
                %s
                """.formatted(kotlinVersion, dependency);
    }

    private static void assertCompilerClosure(
            ZoltLockfile lockfile,
            String version,
            String member) {
        LockPackage compiler = packageEntry(
                lockfile, KOTLIN_COMPILER, DependencyScope.TOOL_KOTLIN, version);
        LockPackage stdlib = packageEntry(
                lockfile, KOTLIN_STDLIB, DependencyScope.TOOL_KOTLIN, version);
        assertTrue(compiler.direct());
        assertFalse(stdlib.direct());
        assertEquals(List.of(member), compiler.members());
        assertEquals(List.of(member), stdlib.members());
        assertEquals(
                List.of("org.jetbrains.kotlin:kotlin-stdlib:" + version + ":jar:tool-kotlin"),
                compiler.dependencies());
    }

    private static LockPackage packageEntry(
            ZoltLockfile lockfile,
            PackageId packageId,
            DependencyScope scope,
            String version) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(packageId))
                .filter(lockPackage -> lockPackage.scope() == scope)
                .filter(lockPackage -> lockPackage.version().equals(version))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Missing " + packageId + ":" + version + " in " + scope.lockfileName()));
    }
}
