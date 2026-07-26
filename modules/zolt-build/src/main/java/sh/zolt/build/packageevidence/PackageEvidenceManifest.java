package sh.zolt.build.packageevidence;

import sh.zolt.build.packaging.PackageMergeDecision;
import sh.zolt.build.packageplan.PackagePlanDependency;
import java.util.List;

public record PackageEvidenceManifest(
        String schema,
        String archive,
        String archiveSha256,
        List<PackageEvidenceArtifact> artifacts,
        List<PackageMergeDecision> uberMergeDecisions,
        String inputFingerprint,
        List<PackageEvidenceOutput> outputs,
        List<PackageEvidenceMaterializedInput> materializedInputs,
        List<PackagePlanDependency> dependencies) {
    public PackageEvidenceManifest {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        uberMergeDecisions = uberMergeDecisions == null ? List.of() : List.copyOf(uberMergeDecisions);
        inputFingerprint = inputFingerprint == null ? "" : inputFingerprint;
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        materializedInputs =
                materializedInputs == null ? List.of() : List.copyOf(materializedInputs);
        dependencies =
                dependencies == null ? List.of() : List.copyOf(dependencies);
    }

    public PackageEvidenceManifest(
            String schema,
            String archive,
            String archiveSha256,
            List<PackageEvidenceArtifact> artifacts) {
        this(
                schema,
                archive,
                archiveSha256,
                artifacts,
                List.of(),
                "",
                List.of(),
                List.of(),
                List.of());
    }

    public PackageEvidenceManifest(
            String schema,
            String archive,
            String archiveSha256) {
        this(schema, archive, archiveSha256, List.of());
    }
}
