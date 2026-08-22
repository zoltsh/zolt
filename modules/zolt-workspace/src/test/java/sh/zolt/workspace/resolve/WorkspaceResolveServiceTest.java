package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.workspace.WorkspaceConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkspaceResolveServiceTest extends WorkspaceResolveServiceTestSupport {
    @Test
    void resolvesWorkspaceMembersIntoRootLockfile() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api", "modules/core"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                "com.example:app" = "1.0.0"
                """);
        member("modules/core", "core", """

                [dependencies]
                "com.example:lib" = "1.0.0"
                """);

        ResolveResult result = service.resolve(tempDir.resolve("apps/api"), tempDir.resolve("cache"), false, false);

        assertEquals(3, result.resolvedCount());
        assertEquals(4, result.downloadCount());
        assertEquals(0, result.conflictCount());
        assertEquals(tempDir.resolve("zolt.lock"), result.lockfilePath());
        assertTrue(Files.isRegularFile(tempDir.resolve("zolt.lock")));
        assertFalse(Files.exists(tempDir.resolve("apps/api/zolt.lock")));
        assertFalse(Files.exists(tempDir.resolve("modules/core/zolt.lock")));

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.projectResolutionFingerprint().orElseThrow().startsWith("sha256:"));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.acme", "core"))
                        && lockPackage.version().equals("0.1.0")
                        && lockPackage.source().equals("workspace")
                        && lockPackage.scope() == DependencyScope.COMPILE
                        && lockPackage.direct()
                        && lockPackage.workspace().orElseThrow().equals("modules/core")
                        && lockPackage.workspaceOutput().orElseThrow().equals("target/classes")
                        && lockPackage.members().equals(List.of("apps/api"))));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "app"))
                        && lockPackage.scope() == DependencyScope.COMPILE
                        && lockPackage.direct()
                        && lockPackage.members().equals(List.of("apps/api"))));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "lib"))
                        && lockPackage.scope() == DependencyScope.COMPILE
                        && lockPackage.direct()
                        && lockPackage.members().equals(List.of("apps/api", "modules/core"))));

        assertTrue(LockfileClasspathPackageConverter.classpathPackages(lockfile, tempDir.resolve("cache"), tempDir).stream()
                .anyMatch(classpathPackage -> classpathPackage.resolvedPackage().jarPath()
                        .equals(tempDir.resolve("modules/core/target/classes"))));
    }

    @Test
    void mergesWorkspacePackageOwners() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api", "apps/worker", "modules/core"]
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);
        member("apps/worker", "worker", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);
        member("modules/core", "core", "");

        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage core = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.acme", "core")))
                .findFirst()
                .orElseThrow();
        assertEquals("workspace", core.source());
        assertEquals("modules/core", core.workspace().orElseThrow());
        assertEquals("target/classes", core.workspaceOutput().orElseThrow());
        assertEquals(List.of("apps/api", "apps/worker"), core.members());
    }

    @Test
    void rejectsUnsafeWorkspaceMemberOutputBeforeWritingLockfile() throws IOException {
        workspace("""
                [workspace]
                name = "bad"

                [workspace.members]
                include = ["apps/api", "modules/core"]
                """);
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);
        member("modules/core", "core", """

                [build.output]
                main = "../classes"
                """);

        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> service.resolve(tempDir, tempDir.resolve("cache"), false, false));

        assertTrue(exception.getMessage().contains("modules/core"), exception.getMessage());
        assertTrue(exception.getMessage().contains("build.output.main"), exception.getMessage());
        assertTrue(exception.getMessage().contains("../classes"), exception.getMessage());
        assertFalse(Files.exists(tempDir.resolve("zolt.lock")));
    }

    @Test
    void preservesDependencyMetadataWhenMergingWorkspacePolicy() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.example:app" = { version = "1.0.0", exclude = ["com.example:lib"] }
                """);

        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        assertEquals(1, result.resolvedCount());
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "app"))));
        assertFalse(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "lib"))));
    }

    @Test
    void usesWorkspacePlatformsForManagedMemberDependencies() throws IOException {
        addPom("com.example", "platform", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>platform</artifactId>
                  <version>1.0.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>app</artifactId>
                        <version>1.0.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [platforms]
                "com.example:platform" = "1.0.0"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.example:app" = { managed = true }
                """);

        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "app"))
                        && lockPackage.version().equals("1.0.0")
                        && lockPackage.direct()));
    }

}
