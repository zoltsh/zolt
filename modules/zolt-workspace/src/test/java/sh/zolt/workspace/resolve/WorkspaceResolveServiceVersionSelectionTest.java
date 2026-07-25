package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.publish.WorkspaceMemberSbomLockProjection;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkspaceResolveServiceVersionSelectionTest extends WorkspaceResolveServiceTestSupport {
    @Test
    void selectsGlobalExternalVersionsAcrossWorkspaceMembers() throws IOException {
        addArtifact("com.example", "other", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>other</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>2.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "lib", "2.0.0", pom("com.example", "lib", "2.0.0"));
        workspace("""
                [workspace]
                name = "bad"
                members = ["apps/api", "apps/worker"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.example:app" = "1.0.0"
                """);
        member("apps/worker", "worker", """

                [dependencies]
                "com.example:other" = "1.0.0"
                """);

        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        assertEquals(3, result.resolvedCount());
        assertEquals(1, result.conflictCount());

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        assertFalse(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "lib"))
                        && lockPackage.version().equals("1.0.0")));
        LockPackage lib = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "lib")))
                .findFirst()
                .orElseThrow();
        assertEquals("2.0.0", lib.version());
        assertEquals(List.of("apps/api", "apps/worker"), lib.members());

        LockPackage app = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "app")))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("com.example:lib:2.0.0:jar:compile"), app.dependencies());
        assertTrue(lockfile.conflicts().stream().anyMatch(conflict ->
                conflict.packageId().equals(new PackageId("com.example", "lib"))
                        && conflict.selectedVersion().equals("2.0.0")
                        && conflict.requestedVersions().equals(List.of("1.0.0", "2.0.0"))));
    }

    @Test
    void directWorkspaceMemberDependencyWinsOverTransitiveWorkspaceRequest() throws IOException {
        addArtifact("com.example", "other", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>other</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>lib</artifactId>
                      <version>2.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "lib", "2.0.0", pom("com.example", "lib", "2.0.0"));
        workspace("""
                [workspace]
                name = "direct-wins"
                members = ["apps/api", "apps/worker"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [dependencies]
                "com.example:lib" = "1.0.0"
                """);
        member("apps/worker", "worker", """

                [dependencies]
                "com.example:other" = "1.0.0"
                """);

        ResolveResult result = service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        assertEquals(2, result.resolvedCount());
        assertEquals(1, result.conflictCount());

        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage lib = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "lib")))
                .findFirst()
                .orElseThrow();
        assertEquals("1.0.0", lib.version());
        assertEquals(List.of("apps/api", "apps/worker"), lib.members());
        assertTrue(lib.direct());

        LockPackage other = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "other")))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("com.example:lib:1.0.0:jar:compile"), other.dependencies());
    }

    @Test
    void newerDevSelectionIsRetraversedForTheRuntimeMemberAndItsSbom() throws IOException {
        addArtifact("com.example", "engine", "1.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>engine</artifactId>
                  <version>1.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>legacy-driver</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact("com.example", "engine", "2.0.0", """
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>engine</artifactId>
                  <version>2.0.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>selected-driver</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);
        addArtifact(
                "com.example",
                "legacy-driver",
                "1.0.0",
                pom("com.example", "legacy-driver", "1.0.0"));
        addArtifact(
                "com.example",
                "selected-driver",
                "1.0.0",
                pom("com.example", "selected-driver", "1.0.0"));
        workspace("""
                [workspace]
                name = "scope-mediation"
                members = ["apps/api", "apps/worker"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("apps/api", "api", """

                [runtime.dependencies]
                "com.example:engine" = "1.0.0"
                """);
        member("apps/worker", "worker", """

                [dev.dependencies]
                "com.example:engine" = "2.0.0"
                """);

        Path cache = tempDir.resolve("cache");
        ResolveResult result = service.resolve(tempDir, cache, false, false);
        ZoltLockfile lockfile = lockfileReader.read(result.lockfilePath());
        LockPackage runtimeEngine = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(new PackageId("com.example", "engine")))
                .filter(lockPackage -> lockPackage.scope() == sh.zolt.dependency.DependencyScope.RUNTIME)
                .findFirst()
                .orElseThrow();

        assertEquals("2.0.0", runtimeEngine.version());
        assertEquals(
                List.of("com.example:selected-driver:1.0.0:jar:runtime"),
                runtimeEngine.dependencies());
        assertFalse(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "legacy-driver"))));

        Workspace workspace = new WorkspaceDiscoveryService().discover(tempDir).orElseThrow();
        List<Path> runtimeEntries = new WorkspaceClasspathService()
                .classpathsFor(workspace, lockfile, cache, "apps/api")
                .runtime()
                .entries();
        assertTrue(runtimeEntries.stream()
                .anyMatch(path -> path.getFileName().toString().equals("engine-2.0.0.jar")));
        assertTrue(runtimeEntries.stream()
                .anyMatch(path -> path.getFileName().toString().equals("selected-driver-1.0.0.jar")));
        assertFalse(runtimeEntries.stream()
                .anyMatch(path -> path.getFileName().toString().contains("legacy-driver")));

        WorkspaceMember api = workspace.members().stream()
                .filter(member -> member.path().equals("apps/api"))
                .findFirst()
                .orElseThrow();
        WorkspaceMemberPolicyResolver policyResolver = new WorkspaceMemberPolicyResolver();
        ZoltLockfile memberSbomLock = new WorkspaceMemberSbomLockProjection().project(
                api.path(),
                policyResolver.merge(workspace, api),
                lockfile,
                workspace,
                policyResolver);
        assertTrue(memberSbomLock.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "selected-driver"))
                        && lockPackage.scope() == sh.zolt.dependency.DependencyScope.RUNTIME));
        assertFalse(memberSbomLock.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(new PackageId("com.example", "legacy-driver"))));
    }
}
