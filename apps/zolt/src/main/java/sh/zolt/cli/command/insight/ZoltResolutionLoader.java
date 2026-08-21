package sh.zolt.cli.command.insight;

import sh.zolt.error.ActionableException;
import sh.zolt.explain.verify.ResolvedModule;
import sh.zolt.explain.verify.ZoltModuleMapper;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.ResolveService;
import sh.zolt.resolve.materialization.RepositoryOverlay;
import sh.zolt.workspace.discovery.ManifestProjectLoader;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.resolve.WorkspaceMemberPolicyResolver;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the Zolt side of the comparison: either a single project or every member of a workspace,
 * each via {@link ResolveService#resolveLockfile} (the same resolver {@code zolt resolve} uses), then
 * maps the resolved {@link LockPackage}s into per-module {@link ResolvedModule}s.
 *
 * <p>Workspace mode resolves each member with the same workspace-root policy merge that
 * {@code zolt resolve --workspace} applies (root {@code [repositories]}/{@code [platforms]} folded in
 * via {@link WorkspaceMemberPolicyResolver}), so member resolution matches the workspace resolver
 * instead of reporting drift on shared root configuration. Repository overlays passed through from
 * {@code --repository-overlay} let overlay-backed dependencies (including directly declared SNAPSHOTs)
 * resolve for the comparison.
 */
final class ZoltResolutionLoader {

    /** Result of loading and resolving the Zolt side. */
    record ZoltResolution(List<ResolvedModule> modules, Map<String, String> memberPaths, String root) {
    }

    private final ResolveService resolveService;
    private final ManifestProjectLoader projectLoader;
    private final ManifestWorkspaceLoader workspaceLoader;
    private final WorkspaceMemberPolicyResolver policyResolver;
    private final ZoltModuleMapper moduleMapper;

    ZoltResolutionLoader(ResolveService resolveService) {
        this(
                resolveService,
                new ManifestProjectLoader(),
                new ManifestWorkspaceLoader(),
                new WorkspaceMemberPolicyResolver(),
                new ZoltModuleMapper());
    }

    ZoltResolutionLoader(
            ResolveService resolveService,
            ManifestProjectLoader projectLoader,
            ManifestWorkspaceLoader workspaceLoader,
            WorkspaceMemberPolicyResolver policyResolver,
            ZoltModuleMapper moduleMapper) {
        this.resolveService = resolveService;
        this.projectLoader = projectLoader;
        this.workspaceLoader = workspaceLoader;
        this.policyResolver = policyResolver;
        this.moduleMapper = moduleMapper;
    }

    ZoltResolution load(Path zoltDir, Path cacheRoot, boolean offline, List<RepositoryOverlay> repositoryOverlays) {
        Path zoltToml = zoltDir.resolve("zolt.toml");
        if (!Files.isRegularFile(zoltToml)) {
            throw new ActionableException(
                    "No zolt.toml found at " + zoltDir + " to compare against Maven.",
                    "Run `zolt explain --emit-toml-output <dir>` to synthesize a draft, then pass "
                            + "--zolt-dir <dir> (or add a zolt.toml at the project root).");
        }
        ResolveOptions options = new ResolveOptions(offline, repositoryOverlays, false)
                .withRetryCommand("zolt explain verify");
        List<ResolvedModule> modules = new ArrayList<>();
        Map<String, String> memberPaths = new LinkedHashMap<>();
        if (isWorkspace(zoltToml)) {
            Workspace workspace = workspaceLoader.load(zoltDir);
            for (WorkspaceMember member : workspace.members()) {
                ResolvedModule module = resolveMember(policyResolver.merge(workspace, member), cacheRoot, options);
                modules.add(module);
                memberPaths.put(module.moduleKey(), member.path());
            }
        } else {
            ProjectConfig config = projectLoader.load(zoltDir);
            ResolvedModule module = resolveMember(config, cacheRoot, options);
            modules.add(module);
            memberPaths.put(module.moduleKey(), ".");
        }
        return new ZoltResolution(modules, memberPaths, zoltDir.toString());
    }

    private ResolvedModule resolveMember(ProjectConfig config, Path cacheRoot, ResolveOptions options) {
        ProjectMetadata metadata = config.project();
        List<LockPackage> packages = resolveService
                .resolveLockfile(config, cacheRoot, options)
                .lockfile()
                .packages();
        return moduleMapper.fromLockPackages(
                metadata.group(), metadata.name(), metadata.version(), packages);
    }

    private static boolean isWorkspace(Path zoltToml) {
        try {
            return Files.readAllLines(zoltToml).stream()
                    .map(String::trim)
                    .anyMatch("[workspace]"::equals);
        } catch (IOException exception) {
            throw new ActionableException(
                    "Could not read " + zoltToml + ": " + exception.getMessage() + ".",
                    "Confirm the file is readable and retry `zolt explain verify`.");
        }
    }
}
