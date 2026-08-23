package sh.zolt.workspace.member;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectConfig;

/**
 * The one member-facing view of a workspace resolution: what a command started inside
 * {@code apps/api} is allowed to see.
 *
 * <p>Design §4.5: a workspace has exactly one authoritative {@code zolt.lock}, at its root. A member
 * command therefore consumes neither a member-local lock (there is none, and planting one must change
 * nothing) nor the unfiltered root lock (which carries every sibling's dependencies and would leak a
 * sibling-only package into that member's SBOM, license report, or POM). It consumes a
 * <em>projection</em>: the member's own slice of the one workspace-wide resolution, carrying the
 * authoritative path and the member identity so every report it feeds stays member-qualified.
 *
 * <p><strong>Why several locks and not one.</strong> The member's slice is not one shape. The graph a
 * supply-chain report must describe (every reachable transitive component, its hashes, its edges) is
 * not the set a POM may declare (authored directs only), is not the packaging closure, and is not the
 * all-scope policy view that dependency and license policy evaluate. Collapsing them would either
 * understate the SBOM or overstate the POM. They are all derived from the same aggregate and the same
 * member identity, so they belong behind one boundary rather than four unrelated projection classes —
 * but they stay four distinct <em>fields</em>.
 *
 * <p><strong>Why lazy.</strong> Each projection carries its own actionable failure: publication-root
 * coverage rejects an incomplete POM, the graph projection rejects a pre-v7 lock. A consumer that
 * never asks for the publication view must not inherit the publication view's failure, so
 * {@code zolt sbom} in a member with unpublishable roots still emits an SBOM. Each view is computed at
 * most once, on first ask.
 *
 * @see MemberResolvedViewService for how each field is projected out of the aggregate
 */
public final class MemberResolvedView {
    private final String memberPath;
    private final Path memberDirectory;
    private final Path authoritativeLockfile;
    private final ProjectConfig effectiveConfig;
    private final boolean bom;
    private final Memo<ZoltLockfile> dependencyGraphLock;
    private final Memo<ZoltLockfile> packageLock;
    private final Memo<ZoltLockfile> policyLock;
    private final Memo<ZoltLockfile> publicationLock;
    private final Memo<List<String>> dependencyGraphRoots;

    MemberResolvedView(
            String memberPath,
            Path memberDirectory,
            Path authoritativeLockfile,
            ProjectConfig effectiveConfig,
            boolean bom,
            Supplier<ZoltLockfile> dependencyGraphLock,
            Supplier<ZoltLockfile> packageLock,
            Supplier<ZoltLockfile> policyLock,
            Supplier<ZoltLockfile> publicationLock,
            Supplier<List<String>> dependencyGraphRoots) {
        this.memberPath = Objects.requireNonNull(memberPath, "Member path must not be null.");
        this.memberDirectory = Objects.requireNonNull(memberDirectory, "Member directory must not be null.");
        this.authoritativeLockfile =
                Objects.requireNonNull(authoritativeLockfile, "Authoritative lockfile must not be null.");
        this.effectiveConfig = Objects.requireNonNull(effectiveConfig, "Effective config must not be null.");
        this.bom = bom;
        this.dependencyGraphLock = new Memo<>(dependencyGraphLock);
        this.packageLock = new Memo<>(packageLock);
        this.policyLock = new Memo<>(policyLock);
        this.publicationLock = new Memo<>(publicationLock);
        this.dependencyGraphRoots = new Memo<>(dependencyGraphRoots);
    }

    /** The member's workspace-relative declared path — its identity in every member-qualified fact. */
    public String memberPath() {
        return memberPath;
    }

    /** Where the member's manifest, sources, and outputs live. */
    public Path memberDirectory() {
        return memberDirectory;
    }

    /**
     * The workspace root's {@code zolt.lock} — the file this whole view was projected out of, and the
     * only lock path a member-facing report may name. A member-local {@code zolt.lock} is never this.
     */
    public Path authoritativeLockfile() {
        return authoritativeLockfile;
    }

    /** The member's config with workspace-root repositories and platforms merged in. */
    public ProjectConfig effectiveConfig() {
        return effectiveConfig;
    }

    /** Whether this member publishes a BOM, whose family closure replaces the ordinary projections. */
    public boolean bom() {
        return bom;
    }

    /**
     * Full-fidelity supply-chain evidence: every component the member's non-publish-only roots reach,
     * transitively and through workspace siblings, carried through with artifact paths, SHA-256 hashes,
     * scopes, policies, and dependency edges intact. This is what an SBOM, a license report, and a
     * license-policy evaluation must describe — and it is exactly what the lightweight classpath
     * projection cannot supply, since that one carries empty dependency roots and member graphs.
     */
    public ZoltLockfile dependencyGraphLock() {
        return dependencyGraphLock.get();
    }

    /** The member's runtime/packaging closure, as the workspace packager itself computes it. */
    public ZoltLockfile packageLock() {
        return packageLock.get();
    }

    /**
     * The member's all-scope policy view: only packages attributed to this member, directness
     * reconstructed from its effective config, graph facts restored from {@code [[memberGraph]]},
     * compile/runtime/provided/dev/test/processor lanes retained.
     */
    public ZoltLockfile policyLock() {
        return policyLock.get();
    }

    /**
     * POM-shaped: the member's publishable authored roots only, which is what a POM may declare.
     * Deliberately narrower than {@link #dependencyGraphLock()} — a POM lists directs, an SBOM lists
     * the closure.
     */
    public ZoltLockfile publicationLock() {
        return publicationLock.get();
    }

    /** The encoded locked roots of this member's dependency graph, for workspace-shaped reports. */
    public List<String> dependencyGraphRoots() {
        return dependencyGraphRoots.get();
    }

    /** Computes once, on first ask, and remembers — including a failure's cause on every re-ask. */
    private static final class Memo<T> {
        private final Supplier<T> supplier;
        private T value;
        private RuntimeException failure;

        Memo(Supplier<T> supplier) {
            this.supplier = Objects.requireNonNull(supplier, "Projection supplier must not be null.");
        }

        T get() {
            if (failure != null) {
                throw failure;
            }
            if (value == null) {
                try {
                    value = supplier.get();
                } catch (RuntimeException exception) {
                    failure = exception;
                    throw exception;
                }
            }
            return value;
        }
    }
}
