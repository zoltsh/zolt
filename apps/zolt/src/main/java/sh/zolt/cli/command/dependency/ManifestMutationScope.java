package sh.zolt.cli.command.dependency;

import sh.zolt.error.ActionableError;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;

/** Authoritative manifest, lockfile, and journal paths for one standalone or workspace-member edit. */
record ManifestMutationScope(
        Path manifestRoot,
        Path manifestPath,
        Path lockRoot,
        Path lockfilePath,
        Path transactionDirectory,
        Workspace workspace) {

    static ManifestMutationScope discover(Path projectRoot, Path lockRoot) {
        Path normalizedProject = projectRoot.toAbsolutePath().normalize();
        Path normalizedLockRoot = lockRoot.toAbsolutePath().normalize();
        Workspace workspace = discoverWorkspace(normalizedProject, normalizedLockRoot);
        if (workspace != null) {
            WorkspaceMember member = workspace.members().stream()
                    .filter(candidate -> candidate.directory().toAbsolutePath().normalize().equals(normalizedProject))
                    .findFirst()
                    .orElse(null);
            if (member != null) {
                String encoded = Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(member.path().getBytes(StandardCharsets.UTF_8));
                return new ManifestMutationScope(
                        normalizedProject,
                        normalizedProject.resolve("zolt.toml"),
                        normalizedLockRoot,
                        normalizedLockRoot.resolve("zolt.lock"),
                        normalizedLockRoot.resolve(".zolt")
                                .resolve(ManifestEditRecovery.WORKSPACE_TRANSACTIONS_DIRECTORY)
                                .resolve(encoded),
                        workspace);
            }
            throw new ZoltConfigException(ActionableError.of(
                    "Manifest mutations require a standalone project or a declared workspace member.",
                    "Run the command from a member directory, or add `.` to [workspace].members before editing "
                            + normalizedProject
                            + "."));
        }
        return new ManifestMutationScope(
                normalizedProject,
                normalizedProject.resolve("zolt.toml"),
                normalizedProject,
                normalizedProject.resolve("zolt.lock"),
                normalizedProject.resolve(".zolt").resolve(ManifestEditRecovery.TRANSACTION_DIRECTORY),
                null);
    }

    private static Workspace discoverWorkspace(Path projectRoot, Path lockRoot) {
        try {
            return new WorkspaceDiscoveryService().discover(projectRoot).orElse(null);
        } catch (WorkspaceConfigException exception) {
            // Project manifests may retain an inert [workspace] domain for source-preservation.
            // Only a containing workspace root makes a discovery failure authoritative here.
            if (!projectRoot.equals(lockRoot)) {
                throw exception;
            }
            return null;
        }
    }
}
