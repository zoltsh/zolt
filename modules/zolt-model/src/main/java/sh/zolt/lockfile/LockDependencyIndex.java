package sh.zolt.lockfile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves {@link LockPackage#dependencies()} edge strings back to the {@link LockPackage} they point at,
 * carrying variant and scope identity end to end. Every edge consumer (tree/why lookups, the SBOM
 * dependency graph, the workspace projections' BFS) builds one of these over its candidate packages
 * and calls {@link #resolve(String)} instead of a bare {@code groupId:artifactId:version} map lookup.
 *
 * <p><strong>Resolution.</strong> A version-3 edge ({@code g:a:v:key:scope}) resolves exactly. A
 * historical edge can resolve only to one candidate after applying its available variant identity.
 * Multiple scope copies therefore make a v1/v2 edge ambiguous, which resolves to nothing rather than
 * silently choosing the first package. Two materially different package sources may not claim the
 * same exact version-3 edge identity.
 */
public final class LockDependencyIndex {
    private final Map<String, LockPackage> byRef = new LinkedHashMap<>();
    private final Map<String, List<LockPackage>> byGav = new LinkedHashMap<>();
    private final Map<String, LockPackage> byArtifactIdentity = new LinkedHashMap<>();

    public LockDependencyIndex(Iterable<LockPackage> packages) {
        for (LockPackage lockPackage : packages) {
            LockDependencyEdge edge = LockDependencyEdge.of(lockPackage);
            LockPackage previous = byRef.putIfAbsent(edge.encode(), lockPackage);
            if (previous != null && !sameTarget(previous, lockPackage)) {
                throw new LockDependencyGraphException(
                        "Dependency edge identity `"
                                + edge.encode()
                                + "` targets multiple locked package sources. Run `zolt resolve --workspace` to reject or regenerate the ambiguous local/released relationship.");
            }
            String artifactIdentity = edge.gav() + ":" + edge.variant().key();
            LockPackage previousArtifact = byArtifactIdentity.putIfAbsent(artifactIdentity, lockPackage);
            if (previousArtifact != null && !sameTarget(previousArtifact, lockPackage)) {
                throw new LockDependencyGraphException(
                        "Maven artifact identity `"
                                + artifactIdentity
                                + "` targets multiple locked package sources across dependency scopes. Run `zolt resolve --workspace` to reject or regenerate the ambiguous local/released relationship.");
            }
            byGav.computeIfAbsent(edge.gav(), key -> new ArrayList<>()).add(lockPackage);
        }
    }

    private static boolean sameTarget(
            LockPackage left,
            LockPackage right) {
        return LockPackageTargetEquivalence.sameTarget(left, right);
    }

    /** Resolves an edge string to the package it targets, honoring variant and scope identity. */
    public Optional<LockPackage> resolve(String edge) {
        Optional<LockDependencyEdge> parsed = LockDependencyEdge.parse(edge);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        LockDependencyEdge target = parsed.orElseThrow();
        if (target.scope().isPresent()) {
            return Optional.ofNullable(byRef.get(target.encode()));
        }
        List<LockPackage> candidates = byGav.getOrDefault(target.gav(), List.of());
        List<LockPackage> matchingVariant = candidates.stream()
                .filter(candidate -> LockArtifactVariant.of(candidate).equals(target.variant()))
                .toList();
        if (matchingVariant.size() == 1) {
            return Optional.of(matchingVariant.getFirst());
        }
        if (target.variant().isDefault() && matchingVariant.isEmpty() && candidates.size() == 1) {
            return Optional.of(candidates.getFirst());
        }
        return Optional.empty();
    }

    /**
     * Resolves a graph edge while refusing the one unsafe legacy case: an unscoped v1/v2 edge with
     * several possible scope or variant copies. Graph-producing commands must fail actionably here
     * instead of silently omitting the relationship.
     */
    public Optional<LockPackage> resolveGraphEdge(String edge, String regenerateCommand) {
        Optional<LockDependencyEdge> parsed = LockDependencyEdge.parse(edge);
        if (parsed.isEmpty()) {
            throw new LockDependencyGraphException(
                    "Dependency edge `"
                            + edge
                            + "` is malformed. Run `"
                            + regenerateCommand
                            + "` to regenerate zolt.lock.");
        }
        LockDependencyEdge target = parsed.orElseThrow();
        if (target.scope().isPresent()) {
            LockPackage resolved = byRef.get(target.encode());
            if (resolved == null) {
                throw dangling(edge, regenerateCommand);
            }
            return Optional.of(resolved);
        }
        List<LockPackage> candidates = byGav.getOrDefault(target.gav(), List.of());
        List<LockPackage> matchingVariant = candidates.stream()
                .filter(candidate -> LockArtifactVariant.of(candidate).equals(target.variant()))
                .toList();
        if (matchingVariant.size() > 1
                || (matchingVariant.isEmpty() && candidates.size() > 1)) {
            throw new LockDependencyGraphException(
                    "Legacy dependency edge `"
                            + edge
                            + "` is ambiguous across locked artifact scopes or variants. Run `"
                            + regenerateCommand
                            + "` to regenerate zolt.lock with lockfile version "
                            + ZoltLockfile.CURRENT_VERSION
                            + " member- and scope-qualified graph evidence.");
        }
        Optional<LockPackage> resolved = resolve(edge);
        if (resolved.isEmpty()) {
            throw dangling(edge, regenerateCommand);
        }
        return resolved;
    }

    private static LockDependencyGraphException dangling(
            String edge,
            String regenerateCommand) {
        return new LockDependencyGraphException(
                "Dangling dependency edge `"
                        + edge
                        + "` does not target any locked package. Run `"
                        + regenerateCommand
                        + "` to regenerate zolt.lock.");
    }
}
