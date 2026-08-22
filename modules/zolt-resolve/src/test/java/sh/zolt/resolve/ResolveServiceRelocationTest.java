package sh.zolt.resolve;

import sh.zolt.resolve.support.ResolveServiceTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ResolveServiceRelocationTest extends ResolveServiceTestSupport {
    @Test
    void transitiveRelocationWritesFinalCoordinateAndDependencyReferenceToLockfile() {
        addArtifact("com.example", "app", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>app</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.legacy</groupId>
                      <artifactId>old-lib</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addPom("com.legacy", "old-lib", "1.0.0", """
                <project>
                  <groupId>com.legacy</groupId>
                  <artifactId>old-lib</artifactId>
                  <version>1.0.0</version>
                  <distributionManagement>
                    <relocation>
                      <groupId>com.modern</groupId>
                      <artifactId>new-lib</artifactId>
                      <version>2.0.0</version>
                    </relocation>
                  </distributionManagement>
                </project>
                """);
        addArtifact("com.modern", "new-lib", "2.0.0", simplePom("com.modern", "new-lib", "2.0.0"));
        Path projectDir = tempDir.resolve("project-transitive-relocation");
        Path cacheRoot = tempDir.resolve("cache-transitive-relocation");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(projectDir, config(), cacheRoot);

        assertEquals(2, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage app = packageFor(lockfile, "com.example", "app");
        LockPackage relocated = packageFor(lockfile, "com.modern", "new-lib");
        assertEquals(List.of("com.modern:new-lib:2.0.0:jar:compile"), app.dependencies());
        assertEquals("2.0.0", relocated.version());
        assertTrue(lockfile.packages().stream().noneMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.legacy", "old-lib"))));
    }

    @Test
    void directAuthoredRelocationIsRejectedWithTheFinalCoordinate() {
        addPom("com.legacy", "old-lib", "1.0.0", """
                <project>
                  <groupId>com.legacy</groupId>
                  <artifactId>old-lib</artifactId>
                  <version>1.0.0</version>
                  <distributionManagement>
                    <relocation>
                      <artifactId>new-lib</artifactId>
                    </relocation>
                  </distributionManagement>
                </project>
                """);
        addArtifact("com.legacy", "new-lib", "1.0.0", simplePom("com.legacy", "new-lib", "1.0.0"));
        Path projectDir = tempDir.resolve("project-inherited-relocation");
        Path cacheRoot = tempDir.resolve("cache-inherited-relocation");
        createDirectory(projectDir);

        ResolveException exception = assertThrows(ResolveException.class, () -> resolveService.resolve(
                projectDir,
                configWithDependencies(java.util.Map.of("com.legacy:old-lib", "1.0.0")),
                cacheRoot));

        assertTrue(exception.getMessage().contains("exact authored dependency-root identity"));
        assertTrue(exception.getMessage().contains("com.legacy:new-lib:1.0.0"));
        assertTrue(exception.getMessage().contains("Replace the declaration"));
    }

    @Test
    void sameIdentityVersionRelocationLocksTheAuthoredIdAndSelectedVersion() {
        addPom("com.example", "library", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>library</artifactId>
                  <version>1.0.0</version>
                  <distributionManagement>
                    <relocation><version>2.0.0</version></relocation>
                  </distributionManagement>
                </project>
                """);
        addArtifact("com.example", "library", "2.0.0", simplePom("com.example", "library", "2.0.0"));
        Path projectDir = tempDir.resolve("project-version-relocation");
        Path cacheRoot = tempDir.resolve("cache-version-relocation");
        createDirectory(projectDir);

        ResolveResult result = resolveService.resolve(
                projectDir,
                configWithDependencies(java.util.Map.of("com.example:library", "1.0.0")),
                cacheRoot);
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());

        assertEquals("com.example:library", lockfile.dependencyRoots().getFirst().packageId().toString());
        assertEquals("2.0.0", lockfile.dependencyRoots().getFirst().version());
        assertEquals("2.0.0", packageFor(lockfile, "com.example", "library").version());
    }

    private static LockPackage packageFor(ZoltLockfile lockfile, String groupId, String artifactId) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId(groupId, artifactId)))
                .findFirst()
                .orElseThrow();
    }
}
