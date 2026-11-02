package sh.zolt.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.support.ResolveServiceTestSupport;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

final class ResolveServiceGroovyToolchainTest extends ResolveServiceTestSupport {
    private static final PackageId GROOVY = new PackageId("org.apache.groovy", "groovy");
    private static final PackageId GROOVY_HELPER =
            new PackageId("org.apache.groovy", "groovy-helper");

    @Test
    void locksAnIsolatedGroovyToolClosureAndRefreshesItOnVersionChangeAndRemoval()
            throws IOException {
        addGroovyArtifacts("4.0.22");
        addGroovyArtifacts("4.0.23");
        Path projectDirectory = tempDir.resolve("groovy-toolchain");
        Path cacheRoot = tempDir.resolve("groovy-toolchain-cache");
        createDirectory(projectDirectory);

        ResolveResult initial = resolveService.resolve(
                projectDirectory,
                config("4.0.22"),
                cacheRoot);
        ZoltLockfile initialLock = lockfileReader.read(initial.lockfilePath());

        assertCompilerClosure(initialLock, DependencyScope.TOOL_GROOVY, "4.0.22");
        assertCompilerClosure(initialLock, DependencyScope.COMPILE, "4.0.22");
        assertEquals(2, initialLock.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(GROOVY))
                .count());

        String initialText = Files.readString(initial.lockfilePath());
        ResolveException changedVersion = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(
                        projectDirectory,
                        config("4.0.23"),
                        cacheRoot,
                        true));

        assertTrue(changedVersion.getMessage().contains("zolt.lock is out of date"));
        assertTrue(changedVersion.getMessage().contains("toolchain.groovy"));
        assertEquals(initialText, Files.readString(initial.lockfilePath()));

        ResolveResult changed = resolveService.resolve(
                projectDirectory,
                config("4.0.23"),
                cacheRoot);
        ZoltLockfile changedLock = lockfileReader.read(changed.lockfilePath());

        assertCompilerClosure(changedLock, DependencyScope.TOOL_GROOVY, "4.0.23");
        assertCompilerClosure(changedLock, DependencyScope.COMPILE, "4.0.22");
        assertFalse(Files.readString(changed.lockfilePath()).equals(initialText));

        String changedText = Files.readString(changed.lockfilePath());
        ResolveException removedToolchain = assertThrows(
                ResolveException.class,
                () -> resolveService.resolve(
                        projectDirectory,
                        config(""),
                        cacheRoot,
                        true));

        assertTrue(removedToolchain.getMessage().contains("zolt.lock is out of date"));
        assertEquals(changedText, Files.readString(changed.lockfilePath()));

        ResolveResult removed = resolveService.resolve(
                projectDirectory,
                config(""),
                cacheRoot);
        ZoltLockfile removedLock = lockfileReader.read(removed.lockfilePath());

        assertCompilerClosure(removedLock, DependencyScope.COMPILE, "4.0.22");
        assertFalse(removedLock.packages().stream()
                .anyMatch(lockPackage -> lockPackage.scope() == DependencyScope.TOOL_GROOVY));
    }

    private void addGroovyArtifacts(String version) {
        addArtifact("org.apache.groovy", "groovy", version, """
                <project>
                  <groupId>org.apache.groovy</groupId>
                  <artifactId>groovy</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.apache.groovy</groupId>
                      <artifactId>groovy-helper</artifactId>
                      <version>%s</version>
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(version, version));
        addArtifact(
                "org.apache.groovy",
                "groovy-helper",
                version,
                simplePom("org.apache.groovy", "groovy-helper", version));
    }

    private static void assertCompilerClosure(
            ZoltLockfile lockfile,
            DependencyScope scope,
            String version) {
        LockPackage compiler = packageEntry(lockfile, GROOVY, scope, version);
        LockPackage helper = packageEntry(lockfile, GROOVY_HELPER, scope, version);

        assertTrue(compiler.direct());
        assertFalse(helper.direct());
        assertTrue(compiler.toolGroups().isEmpty());
        assertTrue(helper.toolGroups().isEmpty());
        assertEquals(
                List.of("org.apache.groovy:groovy-helper:" + version + ":jar:" + scope.lockfileName()),
                compiler.dependencies());
        assertTrue(helper.dependencies().isEmpty());
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

    private ProjectConfig config(String groovyVersion) {
        String toolchain = groovyVersion.isBlank()
                ? ""
                : """

                  [toolchain.groovy]
                  version = "%s"
                  """.formatted(groovyVersion);
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "groovy-toolchain"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [dependencies]
                "org.apache.groovy:groovy" = "4.0.22"
                %s
                """.formatted(baseUri, toolchain));
    }
}
