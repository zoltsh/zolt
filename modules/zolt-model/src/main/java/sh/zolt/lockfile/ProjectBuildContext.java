package sh.zolt.lockfile;

import java.nio.file.Path;
import java.util.Objects;

/**
 * What a core build/test/package operation needs to know about "which project, whose lock".
 *
 * <p>Design §4.5 "Command discovery" gives a command started inside a workspace member two roots: the
 * <em>project root</em> that owns its manifest, sources, and outputs, and the <em>lock root</em> that
 * owns the authoritative {@code zolt.lock}. {@code ProjectCommandContext} decides that pair once at
 * the command boundary. This record is how the answer reaches the core services, which must never
 * re-derive it: a service handed only a member directory can only produce
 * {@code <member>/zolt.lock}, and the lock's content hash is a direct build-fingerprint input, so a
 * stray member-local file would silently change the fingerprint and the build-cache identity of a
 * build whose dependency bytes never moved.
 *
 * <p>{@code memberPath} is the member's workspace-relative path, exactly as authored in
 * {@code [workspace.members]}; standalone projects use {@value #STANDALONE_MEMBER_PATH}. Core
 * operations use it for diagnostics and cache scoping, never to re-derive a root.
 *
 * <p>{@link #standalone(Path)} is an ownership-establishment site — a standalone project directory is
 * its own lock root — and is one of the few places allowed to call {@link ProjectLockfile#in(Path)}.
 * It lives beside that seam rather than in {@code sh.zolt.project} because it is the ANSWER the seam
 * has to be given, and because a project package reaching back into the lockfile package would close
 * a dependency cycle the architecture guardrail forbids.
 */
public record ProjectBuildContext(Path projectRoot, Path lockfilePath, String memberPath) {
    /** The {@code memberPath} of a project that is not a workspace member. */
    public static final String STANDALONE_MEMBER_PATH = ".";

    public ProjectBuildContext {
        Objects.requireNonNull(projectRoot, "Project root must not be null.");
        Objects.requireNonNull(lockfilePath, "Lockfile path must not be null.");
        Objects.requireNonNull(memberPath, "Member path must not be null.");
        if (memberPath.isBlank()) {
            throw new IllegalArgumentException(
                    "Member path must not be blank; a standalone project uses \""
                            + STANDALONE_MEMBER_PATH + "\".");
        }
        projectRoot = projectRoot.toAbsolutePath().normalize();
        lockfilePath = lockfilePath.toAbsolutePath().normalize();
    }

    /** A standalone project: its own directory owns the lock. */
    public static ProjectBuildContext standalone(Path projectDirectory) {
        Path root = Objects.requireNonNull(projectDirectory, "Project directory must not be null.")
                .toAbsolutePath()
                .normalize();
        return new ProjectBuildContext(root, ProjectLockfile.in(root), STANDALONE_MEMBER_PATH);
    }

    /**
     * A workspace member: its own directory for sources and outputs, the workspace root's lockfile for
     * dependency identity. The caller supplies the lockfile path because the workspace boundary already
     * knows it — deriving one here from {@code memberDirectory} is the exact bug this record exists to
     * make unrepresentable.
     */
    public static ProjectBuildContext member(Path memberDirectory, Path lockfilePath, String memberPath) {
        return new ProjectBuildContext(memberDirectory, lockfilePath, memberPath);
    }
}
