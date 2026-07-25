package sh.zolt.workspace.resolve;

import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockfileFreshnessSummary;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockConflict;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.lockfile.toml.LockfileSidecars;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.ResolveOutput;
import sh.zolt.resolve.ResolveResult;
import sh.zolt.resolve.ResolveService;
import sh.zolt.resolve.ResolutionVariant;
import sh.zolt.resolve.metrics.ResolveMetrics;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkspaceResolveService {
    private final WorkspaceDiscoveryService workspaceDiscoveryService;
    private final ResolveService resolveService;
    private final ZoltLockfileReader lockfileReader;
    private final ZoltLockfileWriter lockfileWriter;
    private final WorkspacePolicyMerger policyMerger;
    private final WorkspaceLockfileAggregator lockfileAggregator;

    public WorkspaceResolveService() {
        this(new WorkspaceDiscoveryService(), new ResolveService(), new ZoltLockfileWriter(), new WorkspacePolicyMerger());
    }

    public WorkspaceResolveService(ResolveService resolveService) {
        this(new WorkspaceDiscoveryService(), resolveService, new ZoltLockfileWriter(), new WorkspacePolicyMerger());
    }

    WorkspaceResolveService(
            WorkspaceDiscoveryService workspaceDiscoveryService,
            ResolveService resolveService,
            ZoltLockfileWriter lockfileWriter) {
        this(workspaceDiscoveryService, resolveService, lockfileWriter, new WorkspacePolicyMerger());
    }

    WorkspaceResolveService(
            WorkspaceDiscoveryService workspaceDiscoveryService,
            ResolveService resolveService,
            ZoltLockfileWriter lockfileWriter,
            WorkspacePolicyMerger policyMerger) {
        this.workspaceDiscoveryService = workspaceDiscoveryService;
        this.resolveService = resolveService;
        this.lockfileReader = new ZoltLockfileReader();
        this.lockfileWriter = lockfileWriter;
        this.policyMerger = policyMerger;
        this.lockfileAggregator = new WorkspaceLockfileAggregator();
    }

    public ResolveResult resolve(Path startDirectory, Path cacheRoot, boolean locked, boolean offline) {
        return resolve(startDirectory, cacheRoot, locked, offline, "zolt resolve --workspace");
    }

    public ResolveResult resolve(
            Path startDirectory,
            Path cacheRoot,
            boolean locked,
            boolean offline,
            String retryCommand) {
        return resolve(startDirectory, cacheRoot, locked, ResolveOptions.offline(offline).withRetryCommand(retryCommand));
    }

    public ResolveResult resolveWithCoverageTooling(Path startDirectory, Path cacheRoot) {
        return resolve(startDirectory, cacheRoot, false, ResolveOptions.defaults().withCoverageTooling());
    }

    public ResolveResult resolve(Path startDirectory, Path cacheRoot, boolean locked, ResolveOptions options) {
        Path start = startDirectory.toAbsolutePath().normalize();
        Workspace workspace = workspaceDiscoveryService.discover(start).orElseThrow(() -> new ResolveException(
                "Could not find workspace config. Run `zolt resolve --workspace` from a workspace directory or add zolt.toml with [workspace]."));
        Path lockfilePath = workspace.root().resolve("zolt.lock");
        if (locked && !Files.isRegularFile(lockfilePath)) {
            throw new ResolveException(
                    "Locked workspace resolve requires zolt.lock at "
                            + lockfilePath
                            + ". Run `zolt resolve --workspace` to create it, then retry `zolt resolve --workspace --locked`.");
        }
        options = prepareOptions(lockfilePath, options)
                .withWorkspaceMemberCoordinates(workspaceMemberCoordinates(workspace));

        Map<String, WorkspaceMember> membersByPath = membersByPath(workspace);
        List<WorkspaceMemberResolveOutput> memberOutputs = new ArrayList<>();
        int downloadCount = 0;
        ResolveMetrics metrics = ResolveMetrics.empty();
        for (String memberPath : workspace.buildOrder()) {
            WorkspaceMember member = membersByPath.get(memberPath);
            ResolveOutput output = resolveService.resolveLockfile(
                    policyMerger.merge(workspace, member),
                    cacheRoot,
                    options);
            memberOutputs.add(new WorkspaceMemberResolveOutput(
                    member.path(),
                    output.lockfile(),
                    exportedExternalPackages(member.config())));
            downloadCount += output.downloadCount();
            metrics = metrics.plus(output.metrics());
        }

        List<LockPackage> mediationCandidates =
                workspaceMediationCandidates(workspace, memberOutputs);
        WorkspaceExternalPackageSelector externalSelector = new WorkspaceExternalPackageSelector();
        Map<ResolutionVariant, String> versionOverrides =
                externalSelector.versionOverrides(mediationCandidates);
        List<LockConflict> mediationConflicts =
                externalSelector.versionConflicts(mediationCandidates);
        if (requiresVersionOverrides(memberOutputs, versionOverrides)) {
            List<WorkspaceMemberResolveOutput> remediatedOutputs = new ArrayList<>();
            for (WorkspaceMemberResolveOutput memberOutput : memberOutputs) {
                if (!requiresVersionOverrides(memberOutput, versionOverrides)) {
                    remediatedOutputs.add(memberOutput);
                    continue;
                }
                WorkspaceMember member = membersByPath.get(memberOutput.member());
                ResolveOutput output = resolveService.resolveLockfile(
                        policyMerger.merge(workspace, member),
                        cacheRoot,
                        options.withVersionOverrides(versionOverrides));
                remediatedOutputs.add(new WorkspaceMemberResolveOutput(
                        member.path(),
                        output.lockfile(),
                        exportedExternalPackages(member.config())));
                downloadCount += output.downloadCount();
                metrics = metrics.plus(output.metrics());
            }
            memberOutputs = remediatedOutputs;
        }

        ZoltLockfile lockfile =
                lockfileAggregator.aggregate(workspace, memberOutputs, mediationConflicts);
        if (locked) {
            long started = System.nanoTime();
            verifyLocked(lockfilePath, lockfile);
            metrics = metrics.withLockfileVerificationNanos(elapsedSince(started));
        } else {
            long started = System.nanoTime();
            writeLockfile(lockfilePath, LockfileSidecars.withJavaToolchainBlocksFromExisting(
                    lockfileWriter.write(lockfile),
                    existingLockfileContent(lockfilePath)));
            metrics = metrics.withLockfileWriteNanos(elapsedSince(started));
        }
        return new ResolveResult(
                lockfile.packages().size(),
                downloadCount,
                lockfile.conflicts().size(),
                lockfilePath,
                metrics);
    }

    private static long elapsedSince(long started) {
        return Math.max(0L, System.nanoTime() - started);
    }

    private ResolveOptions prepareOptions(Path lockfilePath, ResolveOptions options) {
        if (!options.includeCoverageTooling() && existingLockfileHasCoverageTooling(lockfilePath)) {
            return options.withCoverageTooling();
        }
        return options;
    }

    private boolean existingLockfileHasCoverageTooling(Path lockfilePath) {
        if (!Files.isRegularFile(lockfilePath)) {
            return false;
        }
        try {
            return lockfileReader.read(lockfilePath).packages().stream()
                    .anyMatch(lockPackage -> lockPackage.scope() == DependencyScope.TOOL_COVERAGE);
        } catch (LockfileReadException exception) {
            return false;
        }
    }

    private void verifyLocked(Path lockfilePath, ZoltLockfile candidate) {
        String existing;
        try {
            existing = Files.readString(lockfilePath);
        } catch (IOException exception) {
            throw new ResolveException(
                    "Could not read zolt.lock at "
                            + lockfilePath
                            + " for locked workspace resolve. Check that the file exists and is readable.",
                    exception);
        }

        String expected = lockfileWriter.write(candidate);
        if (!LockfileSidecars.canonicalDependencyLockfile(existing)
                .equals(LockfileSidecars.canonicalDependencyLockfile(expected))) {
            String changedInputs = changedInputs(existing, candidate);
            throw new ResolveException(
                    "Workspace zolt.lock is out of date."
                            + changedInputs
                            + " Run `zolt resolve --workspace` to refresh it, then retry `zolt resolve --workspace --locked`.");
        }
    }

    private static void writeLockfile(Path lockfilePath, String content) {
        try {
            Files.writeString(lockfilePath, content);
        } catch (IOException exception) {
            throw new ResolveException(
                    "Could not write zolt.lock at "
                            + lockfilePath
                            + ". Check that the directory exists and is writable.",
                    exception);
        }
    }

    private static String existingLockfileContent(Path lockfilePath) {
        if (!Files.isRegularFile(lockfilePath)) {
            return "";
        }
        try {
            return Files.readString(lockfilePath);
        } catch (IOException exception) {
            return "";
        }
    }

    private static String changedInputs(String existing, ZoltLockfile candidate) {
        try {
            return LockfileFreshnessSummary.changedInputs(new ZoltLockfileReader().read(existing), candidate);
        } catch (LockfileReadException exception) {
            return "";
        }
    }

    private static Set<PackageId> workspaceMemberCoordinates(Workspace workspace) {
        Set<PackageId> coordinates = new LinkedHashSet<>();
        for (WorkspaceMember member : workspace.members()) {
            coordinates.add(new PackageId(
                    member.config().project().group(),
                    member.config().project().name()));
        }
        return Set.copyOf(coordinates);
    }

    private static List<LockPackage> workspaceMediationCandidates(
            Workspace workspace,
            List<WorkspaceMemberResolveOutput> memberOutputs) {
        Set<PackageId> workspaceCoordinates = workspaceMemberCoordinates(workspace);
        return memberOutputs.stream()
                .flatMap(output -> output.lockfile().packages().stream())
                .filter(lockPackage -> !workspaceCoordinates.contains(lockPackage.packageId()))
                .toList();
    }

    private static boolean requiresVersionOverrides(
            List<WorkspaceMemberResolveOutput> memberOutputs,
            Map<ResolutionVariant, String> versionOverrides) {
        return memberOutputs.stream()
                .anyMatch(output -> requiresVersionOverrides(output, versionOverrides));
    }

    private static boolean requiresVersionOverrides(
            WorkspaceMemberResolveOutput memberOutput,
            Map<ResolutionVariant, String> versionOverrides) {
        return memberOutput.lockfile().packages().stream()
                .filter(lockPackage -> lockPackage.scope() != DependencyScope.TOOL_EXEC)
                .anyMatch(lockPackage -> {
                    String selected = versionOverrides.get(new ResolutionVariant(
                            lockPackage.packageId(), LockArtifactVariant.of(lockPackage)));
                    return selected != null && !selected.equals(lockPackage.version());
                });
    }

    private static Set<WorkspaceExportedPackage> exportedExternalPackages(ProjectConfig config) {
        Set<WorkspaceExportedPackage> packages = new LinkedHashSet<>();
        config.apiDependencies().keySet().forEach(coordinate ->
                packages.add(exportedPackage(config, coordinate)));
        config.managedApiDependencies().forEach(coordinate ->
                packages.add(exportedPackage(config, coordinate)));
        return Set.copyOf(packages);
    }

    private static WorkspaceExportedPackage exportedPackage(ProjectConfig config, String coordinate) {
        DependencyMetadata metadata = config.dependencyMetadata()
                .get(DependencyMetadata.key("api.dependencies", coordinate));
        String extension = metadata == null || metadata.type() == null ? "jar" : metadata.type();
        String classifier = metadata == null ? null : metadata.classifier();
        return new WorkspaceExportedPackage(
                packageId(coordinate),
                new LockArtifactVariant(extension, java.util.Optional.ofNullable(classifier)));
    }

    private static PackageId packageId(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return new PackageId(parts[0], parts[1]);
    }

    private static Map<String, WorkspaceMember> membersByPath(Workspace workspace) {
        Map<String, WorkspaceMember> members = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            members.put(member.path(), member);
        }
        return members;
    }
}
