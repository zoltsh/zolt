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
import sh.zolt.resolve.ResolveService;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.publish.WorkspaceMemberSbomLockProjection;
import sh.zolt.workspace.publish.WorkspaceMemberPomLockProjection;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.publish.PublishPomGenerator;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class WorkspaceProvidedArtifactMediationTest extends WorkspaceResolveServiceTestSupport {
    private static final PackageId CORE = new PackageId("com.acme", "core");

    @Test
    void thinProviderIsConsumableLocallyAndThroughThePublishedPom() throws IOException {
        workspace("""
                [workspace]
                name = "thin-provider-parity"

                [workspace.members]
                include = ["modules/core", "apps/app"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        member("modules/core", "core", """

                [package]
                mode = "jar"
                """);
        member("apps/app", "app", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);

        var cache = tempDir.resolve("cache");
        service.resolve(tempDir, cache, false, false);
        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        var workspace = new ManifestWorkspaceLoader().discover(tempDir).orElseThrow();
        assertTrue(new WorkspaceClasspathService()
                .classpathsFor(workspace, lockfile, cache, "apps/app")
                .compile()
                .entries()
                .stream()
                .anyMatch(path -> path.toString().contains("modules/core/target/classes")));

        var app = workspace.members().stream()
                .filter(member -> member.path().equals("apps/app"))
                .findFirst()
                .orElseThrow();
        String appPom = new PublishPomGenerator().generate(
                app.config(),
                new WorkspaceMemberPomLockProjection()
                        .project(app.path(), app.config(), lockfile));
        assertTrue(appPom.contains("<artifactId>core</artifactId>"), appPom);
        assertTrue(appPom.contains("<version>0.1.0</version>"), appPom);

        addArtifact("com.acme", "core", "0.1.0", pom("com.acme", "core", "0.1.0"));
        addArtifact("com.acme", "app", "0.1.0", appPom);
        ProjectConfig consumer = new ManifestProjectConfigLoader().load("""
                [project]
                name = "published-consumer"
                version = "1.0.0"
                group = "com.consumer"
                java = 21

                [repositories]
                central = false

                [repositories.test]
                url = "%s"

                [dependencies]
                "com.acme:app" = "0.1.0"
                """.formatted(baseUri));
        ZoltLockfile publishedConsumer = new ResolveService()
                .resolveLockfile(
                        consumer,
                        tempDir.resolve("published-consumer-cache"),
                        false)
                .lockfile();

        assertTrue(publishedConsumer.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(CORE)
                        && lockPackage.version().equals("0.1.0")));
    }

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

        var workspace = new ManifestWorkspaceLoader()
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
    void workspaceShadowConflictHonorsTheConflictPolicy() throws IOException {
        addShadowFixture(true, "");

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> service.resolve(
                        tempDir, tempDir.resolve("cache"), false, false));

        assertTrue(exception.getMessage().contains("[dependencies.policy].conflicts"));
        assertTrue(exception.getMessage().contains("com.acme:core"));
    }

    @Test
    void workspaceShadowCannotOverrideStrictConstraint() throws IOException {
        addShadowFixture(false, """

                [dependencies.constraints]
                "com.acme:core" = { version = "2.8.7" }
                """);

        ResolveException exception = assertThrows(
                ResolveException.class,
                () -> service.resolve(
                        tempDir, tempDir.resolve("cache"), false, false));

        assertTrue(exception.getMessage().contains("strict constraint"));
        assertTrue(exception.getMessage().contains("0.1.0"));
    }

    @Test
    void substitutionIsExplicitAndCanCoexistWithAReleasedSiblingVersion() throws IOException {
        addArtifact("com.acme", "core", "2.8.7", pom("com.acme", "core", "2.8.7"));
        workspace("""
                [workspace]
                name = "explicit-substitution"

                [workspace.members]
                include = ["modules/core", "apps/app", "apps/worker"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        member("modules/core", "core", "");
        member("apps/app", "app", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                """);
        member("apps/worker", "worker", """

                [dependencies]
                "com.acme:core" = "2.8.7"
                """);

        var cache = tempDir.resolve("cache");
        service.resolve(tempDir, cache, false, false);

        ZoltLockfile lockfile = lockfileReader.read(tempDir.resolve("zolt.lock"));
        List<LockPackage> corePackages = lockfile.packages().stream()
                .filter(lockPackage -> lockPackage.packageId().equals(CORE))
                .toList();
        assertEquals(2, corePackages.size());
        assertTrue(corePackages.stream().anyMatch(lockPackage ->
                lockPackage.workspace().equals(java.util.Optional.of("modules/core"))
                        && lockPackage.version().equals("0.1.0")
                        && lockPackage.members().equals(List.of("apps/app"))));
        assertTrue(corePackages.stream().anyMatch(lockPackage ->
                lockPackage.workspace().isEmpty()
                        && lockPackage.version().equals("2.8.7")
                        && lockPackage.members().equals(List.of("apps/worker"))));

        var workspace = new ManifestWorkspaceLoader().discover(tempDir).orElseThrow();
        WorkspaceClasspathService classpaths = new WorkspaceClasspathService();
        assertTrue(classpaths.classpathsFor(workspace, lockfile, cache, "apps/app")
                .compile()
                .entries()
                .stream()
                .anyMatch(path -> path.toString().contains("modules/core/target/classes")));
        assertTrue(classpaths.classpathsFor(workspace, lockfile, cache, "apps/worker")
                .compile()
                .entries()
                .stream()
                .anyMatch(path -> path.toString().contains("core-2.8.7.jar")));

        var app = workspace.members().stream()
                .filter(member -> member.path().equals("apps/app"))
                .findFirst()
                .orElseThrow();
        var worker = workspace.members().stream()
                .filter(member -> member.path().equals("apps/worker"))
                .findFirst()
                .orElseThrow();
        String appPom = new PublishPomGenerator().generate(
                app.config(),
                new WorkspaceMemberPomLockProjection()
                        .project(app.path(), app.config(), lockfile));
        String workerPom = new PublishPomGenerator().generate(
                worker.config(),
                new WorkspaceMemberPomLockProjection()
                        .project(worker.path(), worker.config(), lockfile));
        assertTrue(appPom.contains("<version>0.1.0</version>"), appPom);
        assertTrue(workerPom.contains("<version>2.8.7</version>"), workerPom);

        var policyResolver = new WorkspaceMemberPolicyResolver();
        ZoltLockfile appSbom = new WorkspaceMemberSbomLockProjection().project(
                app.path(), policyResolver.merge(workspace, app), lockfile, workspace, policyResolver);
        ZoltLockfile workerSbom = new WorkspaceMemberSbomLockProjection().project(
                worker.path(), policyResolver.merge(workspace, worker), lockfile, workspace, policyResolver);
        assertFalse(appSbom.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(CORE)
                        && lockPackage.version().equals("2.8.7")));
        assertTrue(workerSbom.packages().stream().anyMatch(lockPackage ->
                lockPackage.packageId().equals(CORE)
                        && lockPackage.version().equals("2.8.7")));
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

                [workspace.members]
                include = ["modules/core", "apps/app"]

                [repositories]
                central = false

                [repositories.test]
                url = "%s"
                """.formatted(baseUri));
        member("modules/core", "core", """

                [dependencies]
                "com.example:new-child" = "1.0.0"
                """);
        member("apps/app", "app", """

                [dependencies]
                "com.acme:core" = { workspace = true }
                "com.example:library" = "1.0.0"
                "com.example:runtime-library" = "1.0.0"

                [dependencies.policy]
                conflicts = "%s"
                %s
                """.formatted(failOnConflict ? "fail" : "resolve", policy));
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
