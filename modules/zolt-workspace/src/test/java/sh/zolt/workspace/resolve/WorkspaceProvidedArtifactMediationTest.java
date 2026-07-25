package sh.zolt.workspace.resolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockDependencyIndex;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.resolve.ResolveException;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.publish.WorkspaceMemberSbomLockProjection;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class WorkspaceProvidedArtifactMediationTest extends WorkspaceResolveServiceTestSupport {
    private static final PackageId CORE = new PackageId("com.acme", "core");

    @Test
    void workspaceArtifactReplacesExternalCompileAndRuntimeGraphs() throws IOException {
        addShadowFixture(false, "");

        service.resolve(tempDir, tempDir.resolve("cache"), false, false);

        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        List<LockPackage> corePackages = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(CORE))
                .toList();
        assertEquals(Set.of(DependencyScope.COMPILE, DependencyScope.RUNTIME), corePackages.stream()
                .map(LockPackage::scope)
                .collect(Collectors.toSet()));
        assertTrue(corePackages.stream().allMatch(lockPackage ->
                lockPackage.workspace().orElseThrow().equals("modules/core")
                        && lockPackage.version().equals("0.1.0")));
        assertFalse(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().artifactId().equals("old-child")));
        assertTrue(lockfile.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().artifactId().equals("new-child")));

        LockPackage library = packageById(lockfile, "library");
        LockPackage runtimeLibrary = packageById(lockfile, "runtime-library");
        assertEquals(List.of("com.acme:core:0.1.0:jar:compile"), library.dependencies());
        assertEquals(List.of("com.acme:core:0.1.0:jar:runtime"), runtimeLibrary.dependencies());
        assertTrue(lockfile.conflicts().stream().anyMatch(conflict ->
                conflict.packageId().equals(CORE)
                        && conflict.selectedVersion().equals("0.1.0")
                        && Set.copyOf(conflict.requestedVersions())
                                .equals(Set.of("0.1.0", "2.8.7"))));

        LockDependencyIndex index = new LockDependencyIndex(lockfile.packages());
        lockfile.packages().stream()
                .flatMap(lockPackage -> lockPackage.dependencies().stream())
                .forEach(edge -> assertTrue(index.resolveGraphEdge(
                        edge, "zolt resolve --workspace").isPresent()));

        var workspace = new WorkspaceDiscoveryService()
                .discover(tempDir)
                .orElseThrow();
        var app = workspace.members().stream()
                .filter(member -> member.path().equals("apps/app"))
                .findFirst()
                .orElseThrow();
        var policyResolver = new WorkspaceMemberPolicyResolver();
        ZoltLockfile appSbomLock =
                new WorkspaceMemberSbomLockProjection().project(
                        app.path(),
                        policyResolver.merge(workspace, app),
                        lockfile,
                        workspace,
                        policyResolver);
        assertFalse(appSbomLock.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().artifactId().equals("old-child")));
        assertTrue(appSbomLock.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().artifactId().equals("new-child")));
    }

    @Test
    void workspaceShadowConflictHonorsFailOnVersionConflict() throws IOException {
        addShadowFixture(true, "");

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> service.resolve(
                        tempDir, tempDir.resolve("cache"), false, false));

        assertTrue(exception.getMessage().contains("failOnVersionConflict"));
        assertTrue(exception.getMessage().contains("com.acme:core"));
    }

    @Test
    void workspaceShadowCannotOverrideStrictConstraint() throws IOException {
        addShadowFixture(false, """

                [dependencyConstraints]
                "com.acme:core" = { version = "2.8.7", kind = "strict" }
                """);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> service.resolve(
                        tempDir, tempDir.resolve("cache"), false, false));

        assertTrue(exception.getMessage().contains("strict constraint"));
        assertTrue(exception.getMessage().contains("0.1.0"));
    }

    private void addShadowFixture(
            boolean failOnConflict,
            String policy) throws IOException {
        addArtifact("com.example", "old-child", "1.0.0", pom("com.example", "old-child", "1.0.0"));
        addArtifact("com.example", "new-child", "1.0.0", pom("com.example", "new-child", "1.0.0"));
        addArtifact("com.acme", "core", "2.8.7", dependencyPom(
                "com.acme", "core", "2.8.7", "com.example", "old-child", "1.0.0", ""));
        addArtifact("com.example", "library", "1.0.0", dependencyPom(
                "com.example", "library", "1.0.0", "com.acme", "core", "2.8.7", ""));
        addArtifact("com.example", "runtime-library", "1.0.0", dependencyPom(
                "com.example", "runtime-library", "1.0.0", "com.acme", "core", "2.8.7", "<scope>runtime</scope>"));
        workspace("""
                [workspace]
                name = "workspace-shadow"
                members = ["modules/core", "apps/app"]

                [repositories]
                test = "%s"
                """.formatted(baseUri));
        member("modules/core", "core", """

                [dependencies]
                "com.example:new-child" = "1.0.0"
                """);
        member("apps/app", "app", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                "com.example:library" = "1.0.0"
                "com.example:runtime-library" = "1.0.0"

                [dependencyPolicy]
                failOnVersionConflict = %s
                %s
                """.formatted(failOnConflict, policy));
    }

    private static LockPackage packageById(
            ZoltLockfile lockfile,
            String artifactId) {
        return lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId()
                        .equals(new PackageId("com.example", artifactId)))
                .findFirst()
                .orElseThrow();
    }

    private static String dependencyPom(
            String group,
            String artifact,
            String version,
            String dependencyGroup,
            String dependencyArtifact,
            String dependencyVersion,
            String dependencyMetadata) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                    <dependency>
                      <groupId>%s</groupId>
                      <artifactId>%s</artifactId>
                      <version>%s</version>
                      %s
                    </dependency>
                  </dependencies>
                </project>
                """.formatted(
                group,
                artifact,
                version,
                dependencyGroup,
                dependencyArtifact,
                dependencyVersion,
                dependencyMetadata);
    }
}
