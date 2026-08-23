package sh.zolt.cli.command.supplychain;

import java.nio.file.Path;
import java.util.Optional;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;
import sh.zolt.workspace.member.MemberProjectionLoader;
import sh.zolt.workspace.member.MemberResolvedView;
import sh.zolt.workspace.member.MemberResolvedViewService;
import sh.zolt.workspace.service.Workspace;

/**
 * Which resolution a supply-chain report describes, for the directory the command was started in.
 *
 * <p>{@code zolt sbom} and {@code zolt licenses} answer the same question and must answer it
 * identically — a license report that enforces policy over a package the SBOM never lists, or vice
 * versa, is a contradiction a user cannot act on. So the routing lives here once rather than twice.
 *
 * <p>The answer that matters is the member one. Finding the workspace root's lock is necessary but not
 * sufficient: that lock holds every member's resolution, and reporting it from a member directory
 * would attest that this member depends on a sibling-only package it never sees. A member therefore
 * reports out of {@link MemberResolvedView#dependencyGraphLock()} — its own reachable closure, with
 * transitive components, hashes, and edges intact.
 */
final class SupplyChainScope {
    private final MemberProjectionLoader memberProjections = new MemberProjectionLoader();

    /**
     * The projected resolution governing {@code startDirectory}, or empty when that directory is not a
     * declared workspace member and therefore reports standalone against a lock of its own.
     *
     * @param command the user-facing command name, so a workspace-lock failure names what was run
     */
    Optional<Reported> member(Path startDirectory, String command) {
        return memberProjections.at(startDirectory, command)
                .map(view -> new Reported(view.effectiveConfig(), view.dependencyGraphLock()));
    }

    /** The authoritative lockfile of a discovered workspace, for the {@code --workspace} reports. */
    static Path workspaceLockfile(Workspace workspace) {
        return MemberResolvedViewService.authoritativeLockfile(workspace);
    }

    /** The config a report's root component describes, and the lock its components come from. */
    record Reported(ProjectConfig config, ZoltLockfile lockfile) {
    }
}
