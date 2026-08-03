package sh.zolt.build.packaging;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packageevidence.PackageEvidenceArtifact;
import sh.zolt.build.packageevidence.PackageEvidenceManifest;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageevidence.PackageEvidenceMaterializedInput;
import sh.zolt.build.packageevidence.PackageEvidenceVerification;
import sh.zolt.build.packageevidence.PackageEvidenceVerifier;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanMaterializedInput;
import sh.zolt.build.packageplan.PackagePlanOutput;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class PackageReuseService {
    private final PackageEvidenceVerifier verifier;

    PackageReuseService() {
        this(new PackageEvidenceVerifier());
    }

    PackageReuseService(PackageEvidenceVerifier verifier) {
        this.verifier = verifier;
    }

    Optional<PackageResult> reuse(
            Path projectDirectory,
            ProjectConfig config,
            BuildResult buildResult,
            PackagePlan plan) {
        Path evidencePath = PackageEvidenceManifestWriter.evidenceManifestPath(plan.archivePath());
        if (!Files.isRegularFile(evidencePath)) {
            return Optional.empty();
        }
        PackageEvidenceVerification verification =
                verifier.verify(projectDirectory, plan, evidencePath);
        if (!verification.valid()) {
            return Optional.empty();
        }
        PackageEvidenceManifest evidence = verification.manifest().orElseThrow();
        Map<String, PackageEvidenceArtifact> evidenceArtifacts = artifactsByClassifier(evidence);
        PackageEvidenceArtifact main = evidenceArtifacts.get("main");
        if (main == null) {
            return Optional.empty();
        }
        return Optional.of(new PackageResult(
                buildResult,
                config.packageSettings().mode(),
                plan.archivePath(),
                plan.runtimeClasspathPath(),
                Optional.of(evidencePath),
                main.entries(),
                hasMainClass(config),
                plan.applicationLayout(),
                supplementalArtifacts(plan, evidenceArtifacts),
                evidence.uberMergeDecisions(),
                materializedInputs(plan, evidence),
                true));
    }

    private static Map<String, PackageEvidenceArtifact> artifactsByClassifier(
            PackageEvidenceManifest evidence) {
        Map<String, PackageEvidenceArtifact> artifacts = new LinkedHashMap<>();
        for (PackageEvidenceArtifact artifact : evidence.artifacts()) {
            artifacts.put(artifact.classifier(), artifact);
        }
        return artifacts;
    }

    private static List<PackageArtifact> supplementalArtifacts(
            PackagePlan plan,
            Map<String, PackageEvidenceArtifact> evidenceArtifacts) {
        return plan.evidence().outputs().stream()
                .filter(PackagePlanOutput::publishArtifact)
                .filter(output -> !"main".equals(output.kind()))
                .map(output -> new PackageArtifact(
                        output.kind(),
                        output.path(),
                        evidenceArtifacts.get(output.kind()).entries()))
                .toList();
    }

    private static List<PackageMaterializedInput> materializedInputs(
            PackagePlan plan,
            PackageEvidenceManifest evidence) {
        Map<String, PackageEvidenceMaterializedInput> recorded = new LinkedHashMap<>();
        for (PackageEvidenceMaterializedInput input : evidence.materializedInputs()) {
            recorded.put(materializedKey(input.coordinate(), input.sourceIdentity()), input);
        }
        return plan.evidence().materializedInputs().stream()
                .map(input -> materializedInput(input, recorded))
                .toList();
    }

    private static PackageMaterializedInput materializedInput(
            PackagePlanMaterializedInput input,
            Map<String, PackageEvidenceMaterializedInput> recorded) {
        PackageEvidenceMaterializedInput evidence =
                recorded.get(materializedKey(input.coordinate(), input.sourceIdentity()));
        return new PackageMaterializedInput(
                input.coordinate(),
                input.sourceDirectory(),
                input.jarPath(),
                input.sourceFingerprint(),
                evidence.sha256());
    }

    private static String materializedKey(String coordinate, String sourceIdentity) {
        return coordinate + "\n" + sourceIdentity;
    }

    private static boolean hasMainClass(ProjectConfig config) {
        return config.packageSettings().mode() != PackageMode.BOM
                && config.project().main().isPresent();
    }
}
