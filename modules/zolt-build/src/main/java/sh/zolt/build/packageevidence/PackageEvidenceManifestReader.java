package sh.zolt.build.packageevidence;

import sh.zolt.build.PackageException;
import sh.zolt.build.packaging.PackageMergeDecision;
import sh.zolt.build.packageplan.PackagePlanDependency;
import sh.zolt.dependency.DependencyScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class PackageEvidenceManifestReader {
    public PackageEvidenceManifest read(Path manifestPath) {
        try {
            String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
            PackageEvidenceJsonReader reader = new PackageEvidenceJsonReader(json, manifestPath);
            return new PackageEvidenceManifest(
                    reader.requiredString("schema"),
                    reader.requiredString("archive"),
                    reader.requiredString("archiveSha256"),
                    artifacts(reader),
                    uberMergeDecisions(reader),
                    reader.optionalString("inputFingerprint").orElse(""),
                    reader.optionalString("buildInputFingerprint").orElse(""),
                    reader.optionalString("applicationOutputFingerprint").orElse(""),
                    outputs(reader),
                    supplementalInputs(reader),
                    workspaceInputs(reader),
                    materializedInputs(reader),
                    dependencies(reader));
        } catch (IOException exception) {
            throw new PackageException(
                    "Could not read package evidence manifest at "
                            + manifestPath
                            + ". Check that the file is readable and retry.",
                    exception);
        }
    }

    private static List<PackagePlanDependency> dependencies(
            PackageEvidenceJsonReader reader) {
        List<PackagePlanDependency> dependencies = new ArrayList<>();
        for (PackageEvidenceJsonReader object :
                reader.objectArray("dependencies")) {
            dependencies.add(new PackagePlanDependency(
                    object.requiredString("coordinate"),
                    object.requiredString("version"),
                    dependencyScope(object.requiredString("scope")),
                    object.stringArray("lanes"),
                    object.requiredBoolean("packageDefault"),
                    object.requiredString("laneDisposition"),
                    object.requiredString("disposition"),
                    object.requiredString("rule"),
                    object.requiredString("location"),
                    object.requiredString("reason"),
                    object.stringArray("policies")));
        }
        return List.copyOf(dependencies);
    }

    private static DependencyScope dependencyScope(String value) {
        for (DependencyScope scope : DependencyScope.values()) {
            if (scope.lockfileName().equals(value)) {
                return scope;
            }
        }
        throw new PackageException(
                "Package evidence dependency scope `"
                        + value
                        + "` is invalid. Regenerate package evidence with `zolt package`.");
    }

    private static List<PackageEvidenceOutput> outputs(
            PackageEvidenceJsonReader reader) {
        List<PackageEvidenceOutput> outputs = new ArrayList<>();
        for (PackageEvidenceJsonReader object : reader.objectArray("outputs")) {
            outputs.add(new PackageEvidenceOutput(
                    object.requiredString("kind"),
                    object.requiredString("path"),
                    object.requiredString("checksumKind"),
                    object.requiredString("sha256")));
        }
        return List.copyOf(outputs);
    }

    private static List<PackageEvidenceMaterializedInput> materializedInputs(
            PackageEvidenceJsonReader reader) {
        List<PackageEvidenceMaterializedInput> inputs = new ArrayList<>();
        for (PackageEvidenceJsonReader object :
                reader.objectArray("materializedInputs")) {
            inputs.add(new PackageEvidenceMaterializedInput(
                    object.requiredString("coordinate"),
                    object.requiredString("sourceIdentity"),
                    object.requiredString("sourceFingerprint"),
                    object.requiredString("jar"),
                    object.requiredString("sha256")));
        }
        return List.copyOf(inputs);
    }

    private static List<PackageEvidenceLiveInput> supplementalInputs(
            PackageEvidenceJsonReader reader) {
        List<PackageEvidenceLiveInput> inputs = new ArrayList<>();
        for (PackageEvidenceJsonReader object :
                reader.objectArray("supplementalInputs")) {
            inputs.add(new PackageEvidenceLiveInput(
                    object.requiredString("kind"),
                    object.requiredString("fingerprint")));
        }
        return List.copyOf(inputs);
    }

    private static List<PackageEvidenceWorkspaceInput> workspaceInputs(
            PackageEvidenceJsonReader reader) {
        List<PackageEvidenceWorkspaceInput> inputs = new ArrayList<>();
        for (PackageEvidenceJsonReader object :
                reader.objectArray("workspaceInputs")) {
            inputs.add(new PackageEvidenceWorkspaceInput(
                    object.requiredString("coordinate"),
                    object.requiredString("identity"),
                    object.requiredString("fingerprint")));
        }
        return List.copyOf(inputs);
    }

    private static List<PackageMergeDecision> uberMergeDecisions(PackageEvidenceJsonReader reader) {
        List<PackageEvidenceJsonReader> objects = reader.objectArray("uberMergeDecisions");
        if (objects.isEmpty()) {
            return List.of();
        }
        List<PackageMergeDecision> decisions = new ArrayList<>();
        for (PackageEvidenceJsonReader object : objects) {
            decisions.add(new PackageMergeDecision(
                    object.requiredString("kind"),
                    object.requiredString("path"),
                    object.nullableString("target"),
                    object.stringArray("sources")));
        }
        return List.copyOf(decisions);
    }

    private static List<PackageEvidenceArtifact> artifacts(PackageEvidenceJsonReader reader) {
        List<PackageEvidenceJsonReader> objects = reader.objectArray("artifacts");
        if (objects.isEmpty()) {
            return List.of();
        }
        List<PackageEvidenceArtifact> artifacts = new ArrayList<>();
        for (PackageEvidenceJsonReader object : objects) {
            artifacts.add(new PackageEvidenceArtifact(
                    object.requiredString("classifier"),
                    object.requiredString("type"),
                    object.requiredString("path"),
                    object.requiredInt("entries"),
                    object.requiredString("sha256")));
        }
        return List.copyOf(artifacts);
    }
}
