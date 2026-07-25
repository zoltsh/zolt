package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.LockPolicyEffect;
import sh.zolt.resolve.ResolutionVariant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WorkspaceMediationPolicyEffects {
    private WorkspaceMediationPolicyEffects() {
    }

    static List<LockPolicyEffect> from(
            List<LockPackage> candidates,
            Map<ResolutionVariant, String> selectedVersions) {
        List<LockPolicyEffect> effects = new ArrayList<>();
        for (LockPackage candidate : candidates) {
            LockArtifactVariant variant = LockArtifactVariant.of(candidate);
            String selected = selectedVersions.get(new ResolutionVariant(
                    candidate.packageId(), variant));
            if (selected == null || selected.equals(candidate.version())) {
                continue;
            }
            for (String member : candidate.members()) {
                effects.add(new LockPolicyEffect(
                        "workspace-mediation",
                        candidate.packageId(),
                        Optional.of(candidate.version()),
                        Optional.of("workspace member " + member),
                        "workspace-mediation: "
                                + candidate.packageId()
                                + (variant.isDefault() ? "" : " variant " + variant.key())
                                + " requested "
                                + candidate.version()
                                + " -> "
                                + selected));
            }
        }
        return effects.stream()
                .distinct()
                .sorted(Comparator.comparing(effect ->
                        effect.packageId()
                                + ":"
                                + effect.requestedVersion().orElse("")
                                + ":"
                                + effect.source().orElse("")))
                .toList();
    }
}
