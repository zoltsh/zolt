package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.resolve.ResolveException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class WorkspaceArtifactIdentityVerifier {
    private WorkspaceArtifactIdentityVerifier() {
    }

    static void requireIdenticalBytes(List<LockPackage> candidates) {
        if (candidates.size() < 2 || distinctMembers(candidates).size() < 2) {
            return;
        }
        Set<String> artifactHashes = new LinkedHashSet<>();
        Set<String> pomHashes = new LinkedHashSet<>();
        for (LockPackage candidate : candidates) {
            artifactHashes.add(artifactHash(candidate).orElse("<missing>"));
            pomHashes.add(candidate.pomSha256().orElse("<missing>"));
        }
        if (artifactHashes.size() == 1
                && pomHashes.size() == 1
                && !artifactHashes.contains("<missing>")
                && !pomHashes.contains("<missing>")) {
            return;
        }
        LockPackage sample = candidates.getFirst();
        throw ResolveException.actionable(
                "Workspace members resolved different bytes for `"
                        + sample.packageId()
                        + ":"
                        + sample.version()
                        + ":"
                        + LockArtifactVariant.of(sample).key()
                        + "`.",
                "Make every member use a repository that serves identical artifact and POM bytes for "
                        + "this coordinate, then run `zolt resolve --workspace` again. Resolutions: "
                        + descriptions(candidates));
    }

    private static Optional<String> artifactHash(LockPackage candidate) {
        return LockArtifactVariant.of(candidate).extension().equals("jar")
                ? candidate.jarSha256()
                : candidate.artifactSha256();
    }

    private static Set<String> distinctMembers(List<LockPackage> candidates) {
        Set<String> members = new LinkedHashSet<>();
        candidates.forEach(candidate -> members.addAll(candidate.members()));
        return members;
    }

    private static String descriptions(List<LockPackage> candidates) {
        return String.join("; ", candidates.stream()
                .map(candidate -> String.join(",", candidate.members())
                        + " source="
                        + candidate.source()
                        + " scope="
                        + candidate.scope().lockfileName()
                        + " artifactSha256="
                        + artifactHash(candidate).orElse("<missing>")
                        + " pomSha256="
                        + candidate.pomSha256().orElse("<missing>"))
                .sorted()
                .toList());
    }
}
