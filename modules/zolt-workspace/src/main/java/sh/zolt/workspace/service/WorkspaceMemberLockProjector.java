package sh.zolt.workspace.service;

import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Projects the workspace's single lock down to one member.
 *
 * <p>Design §4.5: "Member commands project their selected graph from that root lock." The selection
 * is not a filter on package names — it is the member's own dependency closure: its direct roots, the
 * locked packages attributed to it, the packages that cross a workspace boundary from the members it
 * depends on, and each of those members' compiled output resolved through {@code workspaceRoot} plus
 * the lock's {@code workspaceOutput}. Lane and scope survive the projection, so compile, runtime,
 * test, and processor answers stay distinct.
 *
 * <p>The closure itself is not re-derived here. {@link WorkspaceClasspathService} — the same code the
 * workspace build compiles members with — owns it, so a read-only query and the build it describes
 * cannot drift apart.
 */
public final class WorkspaceMemberLockProjector {
    private final ManifestWorkspaceLoader workspaceLoader;
    private final WorkspaceClasspathService classpathService;
    private final WorkspaceClasspathLockFactory lockFactory;

    public WorkspaceMemberLockProjector() {
        this(new ManifestWorkspaceLoader(), new WorkspaceClasspathService());
    }

    WorkspaceMemberLockProjector(
            ManifestWorkspaceLoader workspaceLoader,
            WorkspaceClasspathService classpathService) {
        this.workspaceLoader = workspaceLoader;
        this.classpathService = classpathService;
        this.lockFactory = new WorkspaceClasspathLockFactory();
    }

    /**
     * The member's slice of {@code workspaceLock} as a lock view, without resolving any of it to a
     * path. Reports that describe packages — {@code zolt classpath audit} — answer from this, so they
     * stay readable with a cold artifact cache, exactly as the whole-lock report they replaced was.
     */
    public ZoltLockfile projectLock(
            ZoltLockfile workspaceLock,
            String memberPath,
            Path workspaceRoot) {
        Workspace workspace = requireWorkspace(workspaceRoot);
        requireMember(workspace, memberPath);
        return lockFactory.memberLock(
                new WorkspaceExecutionContext(workspace, workspaceLock, Path.of("")),
                memberPath);
    }

    public MemberLockProjection project(
            ZoltLockfile workspaceLock,
            String memberPath,
            Path workspaceRoot,
            Path cacheRoot) {
        return project(workspaceLock, memberPath, workspaceRoot, cacheRoot, new VerifiedArtifactIndex());
    }

    public MemberLockProjection project(
            ZoltLockfile workspaceLock,
            String memberPath,
            Path workspaceRoot,
            Path cacheRoot,
            VerifiedArtifactIndex artifactIndex) {
        Workspace workspace = requireWorkspace(workspaceRoot);
        requireMember(workspace, memberPath);
        WorkspaceExecutionContext context = new WorkspaceExecutionContext(
                workspace, workspaceLock, cacheRoot, artifactIndex);
        return new MemberLockProjection(
                memberPath,
                classpathService.classpathsFor(context, memberPath, WorkspaceBuildRequirements.testRun()),
                lockFactory.memberLock(context, memberPath));
    }

    private Workspace requireWorkspace(Path workspaceRoot) {
        Path normalized = workspaceRoot.toAbsolutePath().normalize();
        Optional<Workspace> discovered = workspaceLoader.discover(normalized)
                .filter(workspace -> workspace.root().toAbsolutePath().normalize().equals(normalized));
        return discovered.orElseThrow(() -> new WorkspaceConfigException(
                "No workspace is rooted at " + normalized + ", so no member graph can be projected."));
    }

    private static void requireMember(Workspace workspace, String memberPath) {
        boolean declared = workspace.members().stream()
                .anyMatch(member -> member.path().equals(memberPath));
        if (!declared) {
            throw new WorkspaceConfigException(
                    "Workspace member `" + memberPath + "` is not declared in [workspace].members.");
        }
    }
}
