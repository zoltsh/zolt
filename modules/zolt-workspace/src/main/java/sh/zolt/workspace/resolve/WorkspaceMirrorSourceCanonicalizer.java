package sh.zolt.workspace.resolve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPackageTargetEquivalence;

/** Canonicalizes byte-identical external mirrors to one deterministic aggregate-lock source. */
final class WorkspaceMirrorSourceCanonicalizer {
    private WorkspaceMirrorSourceCanonicalizer() {
    }

    static List<LockPackage> canonicalize(List<LockPackage> packages) {
        Map<String, List<LockPackage>> byArtifact = new LinkedHashMap<>();
        for (LockPackage lockPackage : packages) {
            if (lockPackage.workspace().isEmpty()) {
                byArtifact.computeIfAbsent(identity(lockPackage), ignored -> new ArrayList<>())
                        .add(lockPackage);
            }
        }
        Map<String, String> canonicalSources = new LinkedHashMap<>();
        byArtifact.forEach((identity, candidates) -> {
            LockPackage first = candidates.getFirst();
            boolean equivalent = candidates.stream()
                    .allMatch(candidate -> LockPackageTargetEquivalence.sameTarget(first, candidate));
            if (equivalent) {
                canonicalSources.put(
                        identity,
                        candidates.stream()
                                .map(LockPackage::source)
                                .min(Comparator.naturalOrder())
                                .orElseThrow());
            }
        });
        return packages.stream()
                .map(lockPackage -> canonicalSource(
                        lockPackage,
                        canonicalSources.get(identity(lockPackage))))
                .toList();
    }

    private static LockPackage canonicalSource(
            LockPackage lockPackage,
            String source) {
        if (source == null || source.equals(lockPackage.source())) {
            return lockPackage;
        }
        return new LockPackage(
                lockPackage.packageId(),
                lockPackage.version(),
                source,
                lockPackage.scope(),
                lockPackage.direct(),
                lockPackage.jar(),
                lockPackage.pom(),
                lockPackage.jarSha256(),
                lockPackage.pomSha256(),
                lockPackage.artifact(),
                lockPackage.artifactType(),
                lockPackage.artifactSha256(),
                lockPackage.workspace(),
                lockPackage.workspaceOutput(),
                lockPackage.dependencies(),
                lockPackage.members(),
                lockPackage.exportedBy(),
                lockPackage.policies(),
                lockPackage.toolGroups());
    }

    private static String identity(LockPackage lockPackage) {
        return lockPackage.packageId()
                + ":"
                + lockPackage.version()
                + ":"
                + LockArtifactVariant.of(lockPackage).key();
    }
}
