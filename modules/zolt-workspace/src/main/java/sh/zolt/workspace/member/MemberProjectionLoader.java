package sh.zolt.workspace.member;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import sh.zolt.error.ActionableError;
import sh.zolt.error.ActionableException;
import sh.zolt.lockfile.WorkspaceGraphLockCapability;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.workspace.publish.WorkspaceMemberDirectory;

/**
 * The member-command boundary: turns "the directory this command was started in" into the
 * {@link MemberResolvedView} that governs it, or into nothing when the directory is not a member.
 *
 * <p>This is where lock ownership is established for the whole member-facing command matrix. It asks
 * {@link WorkspaceMemberDirectory} first — a config-only test — so a standalone project that merely
 * sits inside a workspace tree keeps its standalone path and its own lock, and only a directory the
 * workspace actually declares is routed. Having settled that, it reads the workspace root's lock once
 * and hands the path down inside the view, so no command below re-derives it and a member-local
 * {@code zolt.lock} is never opened, stat-ed, or named.
 *
 * <p>Returning {@link Optional#empty()} means "not a member — behave standalone". Every failure that
 * IS a member's failure (no root lock, unreadable root lock, a lock too old to carry member-qualified
 * graph evidence) is raised as an {@link ActionableException} naming the workspace command that fixes
 * it, because falling back to standalone there would silently reintroduce the member-local read this
 * whole boundary exists to remove.
 */
public final class MemberProjectionLoader {
    private final WorkspaceMemberDirectory memberDirectory;
    private final ZoltLockfileReader lockfileReader;
    private final MemberResolvedViewService viewService;

    public MemberProjectionLoader() {
        this(new WorkspaceMemberDirectory(), new ZoltLockfileReader(), new MemberResolvedViewService());
    }

    public MemberProjectionLoader(
            WorkspaceMemberDirectory memberDirectory,
            ZoltLockfileReader lockfileReader,
            MemberResolvedViewService viewService) {
        this.memberDirectory = memberDirectory;
        this.lockfileReader = lockfileReader;
        this.viewService = viewService;
    }

    /** The config-only membership test callers settle BEFORE applying any workspace-lock gate. */
    public WorkspaceMemberDirectory memberDirectory() {
        return memberDirectory;
    }

    /**
     * The view governing {@code startDirectory}, or empty when that directory is not a declared
     * workspace member (a standalone project, or the workspace root itself).
     *
     * @param command the user-facing command name, so a lock failure names what the user actually ran
     */
    public Optional<MemberResolvedView> at(Path startDirectory, String command) {
        Optional<WorkspaceMemberDirectory.Membership> located = memberDirectory.membershipAt(startDirectory);
        if (located.isEmpty()) {
            return Optional.empty();
        }
        WorkspaceMemberDirectory.Membership membership = located.orElseThrow();
        Path lockfilePath = MemberResolvedViewService.authoritativeLockfile(membership.workspace());
        ZoltLockfile aggregate = readAggregate(lockfilePath, command);
        return Optional.of(
                viewService.view(membership.workspace(), aggregate, membership.member(), lockfilePath));
    }

    private ZoltLockfile readAggregate(Path lockfilePath, String command) {
        if (!Files.isRegularFile(lockfilePath)) {
            throw new ActionableException(ActionableError.of(
                    "No workspace zolt.lock found at " + lockfilePath + ".",
                    "Run `zolt resolve --workspace` to generate it, then re-run `" + command + "`."));
        }
        ZoltLockfile aggregate;
        try {
            aggregate = lockfileReader.read(lockfilePath);
        } catch (LockfileReadException exception) {
            throw new ActionableException(ActionableError.of(
                    exception.getMessage(),
                    "Run `zolt resolve --workspace` to regenerate the workspace lockfile, then re-run `"
                            + command + "`."));
        }
        WorkspaceGraphLockCapability.requireMemberGraphEvidence(aggregate);
        return aggregate;
    }
}
