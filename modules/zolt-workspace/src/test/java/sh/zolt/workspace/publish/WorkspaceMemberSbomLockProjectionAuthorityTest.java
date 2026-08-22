package sh.zolt.workspace.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;

final class WorkspaceMemberSbomLockProjectionAuthorityTest {
    private final WorkspaceMemberSbomLockProjection projection = new WorkspaceMemberSbomLockProjection();
    private final WorkspaceMemberPolicyResolver resolver = new WorkspaceMemberPolicyResolver();

    @Test
    void exactLockedRootWinsWhenTheLiveConfigNamesAnotherClassifier() {
        String coordinate = "io.netty:netty-transport-native-epoll";
        ProjectConfig memberConfig = config("acme-worker", """

                [dependencies]
                "%s" = "4.1.100.Final"
                """.formatted(coordinate)).withDependencyMetadata(Map.of(
                        DependencyMetadata.key("dependencies", coordinate),
                        new DependencyMetadata(
                                "dependencies", coordinate, null, null, false, null, false, false,
                                List.of(), "linux-x86_64", null)));
        Workspace workspace = workspaceOf(member("acme-worker", memberConfig));
        LockArtifactVariant osx = new LockArtifactVariant("jar", Optional.of("osx-aarch_64"));
        ZoltLockfile aggregate = aggregate(
                List.of(
                        classified("4.1.90.Final", "linux-x86_64", "sibling", DependencyScope.COMPILE),
                        classified("4.1.100.Final", "osx-aarch_64", "acme-worker", DependencyScope.COMPILE)),
                List.of(root("acme-worker", coordinate, "4.1.100.Final", osx,
                        DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE, false, false)));

        ZoltLockfile projected = projection.project(
                "acme-worker", memberConfig, aggregate, workspace, resolver);

        assertEquals(List.of("4.1.100.Final"), projected.packages().stream()
                .map(LockPackage::version)
                .toList());
        assertTrue(projected.packages().getFirst().jar().orElseThrow().endsWith("osx-aarch_64.jar"));
        assertEquals(osx, projected.dependencyRoots().getFirst().variant());
    }

    @Test
    void refusesToLaunderAPreV7Aggregate() {
        ProjectConfig memberConfig = config("acme-worker", "");
        Workspace workspace = workspaceOf(member("acme-worker", memberConfig));
        ZoltLockfile legacy = new ZoltLockfile(6, List.of(), List.of());

        PublishException exception = assertThrows(
                PublishException.class,
                () -> projection.project("acme-worker", memberConfig, legacy, workspace, resolver));

        assertTrue(exception.getMessage().contains("version " + ZoltLockfile.CURRENT_VERSION));
        assertTrue(exception.getMessage().contains("zolt resolve --workspace"));
    }

    @Test
    void omitsPublishOnlyMetadataAndOptionalSiblingRoots() {
        ProjectConfig appConfig = config("app", "");
        ProjectConfig coreConfig = config("core", "");
        Workspace workspace = workspaceOf(member("apps/app", appConfig), member("modules/core", coreConfig));
        LockPackage core = workspacePackage("core", "modules/core", "apps/app", DependencyScope.COMPILE);
        LockPackage optional = external("optional", "modules/core", DependencyScope.COMPILE);
        LockDependencyRoot appRoot = root(
                "apps/app", "com.acme:core", "1.0.0", LockArtifactVariant.defaultVariant(),
                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE, false, false);
        LockDependencyRoot optionalRoot = root(
                "modules/core", "com.example:optional", "1.0.0", LockArtifactVariant.defaultVariant(),
                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE, true, false);
        LockDependencyRoot publishOnly = root(
                "apps/app", "com.example:metadata-only", "9.0.0", LockArtifactVariant.defaultVariant(),
                DependencyLane.API, null, false, true);

        ZoltLockfile projected = projection.project(
                "apps/app",
                appConfig,
                aggregate(List.of(core, optional), List.of(appRoot, optionalRoot, publishOnly)),
                workspace,
                resolver);

        assertEquals(List.of("core"), projected.packages().stream()
                .map(lockPackage -> lockPackage.packageId().artifactId())
                .toList());
        assertEquals(List.of(appRoot), projected.dependencyRoots());
    }

    @Test
    void populatesTheExactWorkspaceScopeOccurrence() {
        ProjectConfig appConfig = config("app", "");
        ProjectConfig coreConfig = config("core", "");
        Workspace workspace = workspaceOf(member("apps/app", appConfig), member("modules/core", coreConfig));
        LockPackage testCore = workspacePackage("core", "modules/core", "apps/app", DependencyScope.TEST);
        LockPackage compileCore = workspacePackage("core", "modules/core", "apps/app", DependencyScope.COMPILE);
        LockPackage helper = external("helper", "modules/core", DependencyScope.COMPILE);
        LockDependencyRoot appRoot = root(
                "apps/app", "com.acme:core", "1.0.0", LockArtifactVariant.defaultVariant(),
                DependencyLane.IMPLEMENTATION, DependencyScope.COMPILE, false, false);
        LockDependencyRoot helperRoot = root(
                "modules/core", "com.example:helper", "1.0.0", LockArtifactVariant.defaultVariant(),
                DependencyLane.API, DependencyScope.COMPILE, false, false);

        ZoltLockfile projected = projection.project(
                "apps/app",
                appConfig,
                aggregate(List.of(testCore, compileCore, helper), List.of(appRoot, helperRoot)),
                workspace,
                resolver);

        assertEquals(DependencyScope.COMPILE, projected.packages().getFirst().scope());
        assertEquals(List.of("com.example:helper:1.0.0:jar:compile"),
                projected.packages().getFirst().dependencies());
    }

    private static ProjectConfig config(String name, String body) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "%s"
                version = "1.0.0"
                group = "com.acme"
                java = 21
                %s
                """.formatted(name, body));
    }

    private static WorkspaceMember member(String path, ProjectConfig config) {
        return new WorkspaceMember(path, Path.of("/ws").resolve(path), config);
    }

    private static Workspace workspaceOf(WorkspaceMember... members) {
        List<String> paths = java.util.Arrays.stream(members).map(WorkspaceMember::path).toList();
        return new Workspace(
                Path.of("/ws"),
                Path.of("/ws/zolt.toml"),
                new WorkspaceConfig("acme", paths, List.of(), Map.of(), Map.of()),
                List.of(members));
    }

    private static ZoltLockfile aggregate(List<LockPackage> packages, List<LockDependencyRoot> roots) {
        return new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                packages,
                List.of(),
                List.of(),
                List.of(),
                roots);
    }

    private static LockDependencyRoot root(
            String member,
            String coordinate,
            String version,
            LockArtifactVariant variant,
            DependencyLane lane,
            DependencyScope scope,
            boolean optional,
            boolean publishOnly) {
        String[] parts = coordinate.split(":", 2);
        return new LockDependencyRoot(
                member,
                new PackageId(parts[0], parts[1]),
                version,
                variant,
                lane,
                Optional.ofNullable(scope),
                optional,
                publishOnly);
    }

    private static LockPackage classified(
            String version,
            String classifier,
            String member,
            DependencyScope scope) {
        String base = "io/netty/netty-transport-native-epoll/" + version
                + "/netty-transport-native-epoll-" + version;
        return lockPackage(
                new PackageId("io.netty", "netty-transport-native-epoll"),
                version,
                scope,
                Optional.of(base + "-" + classifier + ".jar"),
                Optional.empty(),
                member);
    }

    private static LockPackage workspacePackage(
            String artifact,
            String workspacePath,
            String member,
            DependencyScope scope) {
        return new LockPackage(
                new PackageId("com.acme", artifact),
                "1.0.0",
                "workspace",
                scope,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(workspacePath),
                Optional.of("target/classes"),
                List.of(),
                List.of(member),
                List.of(),
                List.of(),
                List.of());
    }

    private static LockPackage external(String artifact, String member, DependencyScope scope) {
        return lockPackage(
                new PackageId("com.example", artifact),
                "1.0.0",
                scope,
                Optional.empty(),
                Optional.empty(),
                member);
    }

    private static LockPackage lockPackage(
            PackageId packageId,
            String version,
            DependencyScope scope,
            Optional<String> jar,
            Optional<String> workspace,
            String member) {
        return new LockPackage(
                packageId,
                version,
                workspace.isPresent() ? "workspace" : "maven-central",
                scope,
                false,
                jar,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                workspace,
                Optional.empty(),
                List.of(),
                List.of(member),
                List.of(),
                List.of(),
                List.of());
    }
}
