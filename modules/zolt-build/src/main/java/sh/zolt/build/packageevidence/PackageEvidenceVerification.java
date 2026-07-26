package sh.zolt.build.packageevidence;

import java.util.List;
import java.util.Optional;

public record PackageEvidenceVerification(
        List<String> problems,
        Optional<PackageEvidenceManifest> manifest) {
    public PackageEvidenceVerification {
        problems = problems == null ? List.of() : List.copyOf(problems);
        manifest = manifest == null ? Optional.empty() : manifest;
    }

    public boolean valid() {
        return problems.isEmpty() && manifest.isPresent();
    }
}
