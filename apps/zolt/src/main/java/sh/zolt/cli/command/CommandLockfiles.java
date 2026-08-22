package sh.zolt.cli.command;

import sh.zolt.build.lockfile.ArtifactIntegrityVerifier;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.lockfile.ContentAddressedLockCapability;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.ResolveService;
import sh.zolt.resolve.fingerprint.ProjectResolutionFingerprint;
import sh.zolt.cli.command.CommandServiceBundles.CommandResolveServices;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.resolve.WorkspaceLockFreshness;
import sh.zolt.workspace.resolve.WorkspaceLockFreshnessService;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.perf.TimingRecorder;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class CommandLockfiles {
    public static final String WORKSPACE_FRESHNESS_PHASE = "workspace lock freshness";

    private final ProjectResolve projectResolve;
    private final ManifestWorkspaceLoader workspaceLoader;
    private final WorkspaceLockFreshnessService workspaceLockFreshnessService;
    private final ZoltLockfileReader lockfileReader;
    private VerifiedArtifactIndex artifactIndex;

    @FunctionalInterface
    interface ProjectResolve {
        void resolve(
                Path workingDirectory,
                ProjectConfig config,
                Path cacheRoot,
                boolean locked,
                ResolveOptions options);
    }

    public CommandLockfiles() {
        this(CommandFrameworkServices.resolveCommandServices());
    }

    private CommandLockfiles(CommandResolveServices services) {
        this(
                services.resolveService(),
                new ManifestWorkspaceLoader(),
                services.workspaceResolveService());
    }

    public CommandLockfiles(
            ResolveService resolveService,
            ManifestWorkspaceLoader workspaceLoader,
            WorkspaceResolveService workspaceResolveService) {
        this(
                (workingDirectory, config, cacheRoot, locked, options) -> resolveService.resolve(
                        workingDirectory, config, cacheRoot, locked, options),
                workspaceLoader,
                workspaceResolveService,
                new ZoltLockfileReader(),
                new VerifiedArtifactIndex());
    }

    CommandLockfiles(
            ProjectResolve projectResolve,
            ManifestWorkspaceLoader workspaceLoader,
            WorkspaceResolveService workspaceResolveService,
            ZoltLockfileReader lockfileReader,
            VerifiedArtifactIndex artifactIndex) {
        this.projectResolve = projectResolve;
        this.workspaceLoader = workspaceLoader;
        this.workspaceLockFreshnessService = new WorkspaceLockFreshnessService(
                workspaceLoader,
                workspaceResolveService);
        this.lockfileReader = lockfileReader;
        this.artifactIndex = artifactIndex;
    }

    public VerifiedArtifactIndex requireFreshLockfile(
            Path workingDirectory,
            ProjectConfig config,
            Path cacheRoot,
            boolean offline) {
        return requireFreshLockfile(workingDirectory, config, cacheRoot, offline, "zolt resolve");
    }

    public VerifiedArtifactIndex requireFreshLockfile(
            Path workingDirectory,
            ProjectConfig config,
            Path cacheRoot,
            boolean offline,
            String retryCommand) {
        Path lockfilePath = workingDirectory.resolve("zolt.lock");
        if (!Files.isRegularFile(lockfilePath)) {
            return artifactIndex;
        }
        ZoltLockfile lockfile = lockfileReader.read(lockfilePath);
        ContentAddressedLockCapability.requireExecutableLockfile(lockfile, "zolt resolve");
        boolean artifactsReady = lockedArtifactsReady(lockfile, cacheRoot);
        if (matchesProjectResolutionFingerprint(lockfilePath, config) && artifactsReady) {
            return artifactIndex;
        }
        redirectWorkspaceMemberToWorkspacePath(workingDirectory, retryCommand);
        projectResolve.resolve(
                workingDirectory,
                config,
                cacheRoot,
                true,
                ResolveOptions.offline(offline).withRetryCommand(retryCommand));
        artifactIndex = new VerifiedArtifactIndex();
        return artifactIndex;
    }

    private void redirectWorkspaceMemberToWorkspacePath(Path workingDirectory, String retryCommand) {
        Path normalizedDirectory = workingDirectory.toAbsolutePath().normalize();
        Optional<Workspace> workspace = workspaceLoader.discover(normalizedDirectory);
        if (workspace.isEmpty()) {
            return;
        }
        Optional<WorkspaceMember> member = workspace.orElseThrow().members().stream()
                .filter(candidate -> candidate.directory().toAbsolutePath().normalize().equals(normalizedDirectory))
                .findFirst();
        if (member.isEmpty()) {
            return;
        }
        String memberPath = member.orElseThrow().path();
        throw ResolveException.actionable(
                "zolt.lock is out of date for workspace member `" + memberPath + "`.",
                "This directory is a member of the workspace at "
                        + workspace.orElseThrow().root()
                        + ", whose lockfile a member-directory build never refreshes. "
                        + "Run `" + retryCommand + " --workspace --member " + memberPath
                        + "` to build it through the workspace lock.");
    }

    public void requireFreshWorkspaceLockfile(Path workingDirectory, Path cacheRoot, boolean offline) {
        requireFreshWorkspaceLockfile(workingDirectory, cacheRoot, offline, "zolt resolve --workspace");
    }

    public void requireFreshWorkspaceLockfile(
            Path workingDirectory,
            Path cacheRoot,
            boolean offline,
            String retryCommand) {
        workspaceLockFreshnessService.requireFresh(workingDirectory, cacheRoot, offline, retryCommand);
    }

    public WorkspacePlanTarget requireFreshWorkspacePlanTarget(
            Path workingDirectory,
            Path cacheRoot,
            boolean offline,
            String retryCommand) {
        return workspaceLockFreshnessService.requireFresh(
                        workingDirectory, cacheRoot, offline, retryCommand)
                .map(freshness -> WorkspacePlanTarget.of(
                        freshness.workspace(),
                        freshness.discoveryNanos(),
                        freshness.artifactIndex()))
                .orElseGet(() -> WorkspacePlanTarget.at(workingDirectory));
    }

    public WorkspacePlanTarget requireFreshWorkspaceLockfile(
            TimingRecorder timings,
            Path workingDirectory,
            Path cacheRoot,
            boolean offline) {
        return requireFreshWorkspaceLockfile(
                timings, workingDirectory, cacheRoot, offline, "zolt resolve --workspace");
    }

    /**
     * Gates the command on a current root lock and hands back the workspace that proved it, so build
     * planning reuses that snapshot instead of walking every member config again. Recorded as its own
     * phase: every workspace command pays this before any timed work starts.
     */
    public WorkspacePlanTarget requireFreshWorkspaceLockfile(
            TimingRecorder timings,
            Path workingDirectory,
            Path cacheRoot,
            boolean offline,
            String retryCommand) {
        return timings.measure(
                        WORKSPACE_FRESHNESS_PHASE,
                        () -> workspaceLockFreshnessService.requireFresh(
                                workingDirectory, cacheRoot, offline, retryCommand),
                        CommandLockfiles::freshnessAttributes)
                .map(freshness -> WorkspacePlanTarget.of(
                        freshness.workspace(),
                        freshness.discoveryNanos(),
                        freshness.artifactIndex()))
                .orElseGet(() -> WorkspacePlanTarget.at(workingDirectory));
    }

    private static Map<String, String> freshnessAttributes(
            Optional<WorkspaceLockFreshness> freshness) {
        return freshness
                .map(value -> Map.of(
                        "workspaceLockFreshness", value.outcome().label(),
                        "workspaceLockResolutionSkipped",
                                Boolean.toString(value.resolutionSkipped()),
                        "workspaceMembers",
                                Integer.toString(value.workspace().members().size())))
                .orElseGet(() -> Map.of("workspaceLockFreshness", "no-workspace"));
    }

    private boolean lockedArtifactsReady(ZoltLockfile lockfile, Path cacheRoot) {
        try {
            new ArtifactIntegrityVerifier(artifactIndex).verify(lockfile, cacheRoot);
            return true;
        } catch (LockfileReadException exception) {
            return false;
        } catch (IllegalArgumentException exception) {
            throw LockfileReadException.actionable(
                    "zolt.lock references an unsafe artifact cache path.",
                    "Correct the lockfile path or regenerate zolt.lock with `zolt resolve`.",
                    exception);
        }
    }

    static boolean matchesProjectResolutionFingerprint(Path lockfilePath, ProjectConfig config) {
        String expected = ProjectResolutionFingerprint.fingerprint(config);
        try (BufferedReader lines = Files.newBufferedReader(lockfilePath)) {
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.startsWith("[[")) {
                    return false;
                }
                if (line.startsWith("projectResolutionFingerprint = \"") && line.endsWith("\"")) {
                    String recorded = line.substring(
                            "projectResolutionFingerprint = \"".length(),
                            line.length() - 1);
                    return expected.equals(recorded);
                }
            }
            return false;
        } catch (IOException exception) {
            throw LockfileReadException.actionable(
                    "Could not read zolt.lock at " + lockfilePath + " while checking lockfile freshness.",
                    "Check that the file exists and is readable.",
                    exception);
        }
    }
}
