package sh.zolt.cli.command;

import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.discovery.ManifestProject;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The command boundary: everything a command needs to know about "the project here", loaded once.
 *
 * <p>Design §4.5 "Command discovery" gives a command started inside a workspace member two roots, not
 * one. {@link #projectRoot()} is where its manifest, sources, and outputs live; {@link #lockRoot()} is
 * the directory that owns the authoritative {@code zolt.lock}. For a standalone project the two are
 * the same directory and {@link #memberPath()} is {@code "."}; for a member they differ and
 * {@code memberPath} is the member's workspace-relative path, which discovery guarantees equals its
 * authored {@code [workspace.members]} entry.
 *
 * <p>Commands never compare the two themselves. {@link #workspaceMember()} answers the one question
 * that changes what a command does — whether this directory is a member, and therefore whether the
 * command must route through its workspace service with {@link #memberSelection()} rather than run
 * standalone against a lock of its own.
 */
public record ProjectCommandContext(
        ManifestProject project,
        Path projectRoot,
        Optional<Path> workspaceRoot,
        Path lockRoot,
        Path lockfilePath,
        String memberPath) {
    public ProjectCommandContext {
        Objects.requireNonNull(project, "Project must not be null.");
        Objects.requireNonNull(projectRoot, "Project root must not be null.");
        Objects.requireNonNull(workspaceRoot, "Workspace root must not be null.");
        Objects.requireNonNull(lockRoot, "Lock root must not be null.");
        Objects.requireNonNull(lockfilePath, "Lockfile path must not be null.");
        Objects.requireNonNull(memberPath, "Member path must not be null.");
    }

    /** Loads the project at {@code projectDirectory} and derives its command boundary. */
    public static ProjectCommandContext load(ManifestProjectLoader projectLoader, Path projectDirectory) {
        return of(projectLoader.project(projectDirectory));
    }

    /** The command boundary for an already-discovered project. */
    public static ProjectCommandContext of(ManifestProject project) {
        Path projectRoot = project.directory().toAbsolutePath().normalize();
        Optional<Path> workspaceRoot = project.workspaceRoot()
                .map(root -> root.toAbsolutePath().normalize());
        Path lockRoot = workspaceRoot.orElse(projectRoot);
        return new ProjectCommandContext(
                project,
                projectRoot,
                workspaceRoot,
                lockRoot,
                ProjectLockfile.in(lockRoot),
                CommandProjectLockfile.memberPath(project));
    }

    public ProjectConfig config() {
        return project.config();
    }

    /**
     * This boundary's answer, in the shape core build, test, and package operations consume. Design
     * §4.5: the boundary decides {@code (projectRoot, lockfilePath, memberPath)} once and hands it
     * down; nothing downstream re-derives any of the three.
     */
    public ProjectBuildContext buildContext() {
        return new ProjectBuildContext(projectRoot, lockfilePath, memberPath);
    }

    /**
     * Whether this directory is a workspace member. A member command routes through the workspace
     * service — the workspace owns the lock, the provider closure, and the build order — so this, not
     * the {@code --workspace} flag, is what decides which path a command takes.
     */
    public boolean workspaceMember() {
        return workspaceRoot.isPresent();
    }

    /**
     * Selects exactly this member, dependency-expanded. The expansion is the point: a member command
     * must build the workspace providers it compiles and runs against, in workspace build order, which
     * is what {@code --workspace --member <path>} already means.
     */
    public WorkspaceSelectionRequest memberSelection() {
        return new WorkspaceSelectionRequest(false, List.of(memberPath));
    }

    /** The command that regenerates this project's lockfile, named for the scope that owns it. */
    public String resolveCommand() {
        return workspaceMember() ? "zolt resolve --workspace" : "zolt resolve";
    }
}
