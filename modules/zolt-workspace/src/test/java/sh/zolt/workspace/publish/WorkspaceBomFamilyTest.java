package sh.zolt.workspace.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.BomSettings;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.PackageMode;
import sh.zolt.project.PackageSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.PublicationMetadata;
import sh.zolt.workspace.WorkspaceConfig;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WorkspaceBomFamilyTest {
    private final WorkspaceBomFamily family = new WorkspaceBomFamily();

    @Test
    void membersTrueSelectsEverySiblingExceptTheBomItself() {
        WorkspaceMember core = member("acme-core", jarConfig("acme-core"));
        WorkspaceMember http = member("acme-http", jarConfig("acme-http"));
        WorkspaceMember bom = member("acme-bom", bomConfig("acme-bom"));
        Workspace workspace = new Workspace(
                Path.of("/ws"),
                Path.of("/ws/zolt.toml"),
                new WorkspaceConfig(
                        "platform-family",
                        List.of("acme-core", "acme-http", "acme-bom"),
                        List.of(),
                        Map.of(),
                        Map.of()),
                List.of(core, http, bom));
        // Only acme-core is depended upon (so only it has a workspace package in the aggregated lock);
        // acme-http's version must fall back to its own declared version.
        ZoltLockfile aggregated = new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                List.of(workspacePackage("com.acme", "acme-core", "1.0.0")),
                List.of());

        ZoltLockfile familyLock = family.familyLock(workspace, aggregated, bom);

        List<String> coordinates = familyLock.packages().stream()
                .map(lockPackage -> lockPackage.packageId().groupId() + ":" + lockPackage.packageId().artifactId())
                .toList();
        assertEquals(List.of("com.acme:acme-core", "com.acme:acme-http"), coordinates);
        assertFalse(coordinates.contains("com.acme:acme-bom"));
        assertTrue(familyLock.packages().stream().allMatch(lockPackage -> lockPackage.version().equals("1.0.0")));
        assertTrue(familyLock.packages().stream().allMatch(lockPackage -> lockPackage.workspace().isPresent()));
    }

    private static WorkspaceMember member(String name, ProjectConfig config) {
        return new WorkspaceMember(name, Path.of("/ws/" + name), config);
    }

    private static ProjectConfig jarConfig(String name) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, "1.0.0", "com.acme", "21", Optional.empty()),
                Map.of("central", ProjectConfig.MAVEN_CENTRAL),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private static ProjectConfig bomConfig(String name) {
        BomSettings bom = new BomSettings(new BomSettings.Members(true, List.of(), List.of()), List.of(), List.of());
        return ProjectConfigs.withDirectDependencies(
                        new ProjectMetadata(name, "1.0.0", "com.acme", "21", Optional.empty()),
                        Map.of("central", ProjectConfig.MAVEN_CENTRAL),
                        Map.of(),
                        Map.of(),
                        BuildSettings.defaults())
                .withPackageSettings(new PackageSettings(PackageMode.BOM, false, false, false,
                        PublicationMetadata.empty()).withBom(bom));
    }

    private static LockPackage workspacePackage(String group, String artifact, String version) {
        return new LockPackage(
                new PackageId(group, artifact),
                version,
                "workspace",
                DependencyScope.COMPILE,
                true,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(artifact),
                Optional.of("target/classes"),
                List.of());
    }
}
