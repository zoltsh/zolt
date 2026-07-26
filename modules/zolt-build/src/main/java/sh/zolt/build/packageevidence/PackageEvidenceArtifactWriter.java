package sh.zolt.build.packageevidence;

import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.displayPath;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.indent;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.intField;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.stringField;

import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanOutput;
import sh.zolt.build.packaging.PackageArtifact;
import sh.zolt.build.packaging.PackageResult;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PackageEvidenceArtifactWriter {
    private PackageEvidenceArtifactWriter() {
    }

    static void write(
            StringBuilder json,
            Path projectRoot,
            PackagePlan plan,
            PackageResult result,
            List<PackageArtifact> artifacts) {
        List<ArtifactEvidence> entries = entries(plan, result, artifacts);

        indent(json, 1).append("\"artifacts\": [");
        if (!entries.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < entries.size(); index++) {
                ArtifactEvidence entry = entries.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "classifier", entry.classifier(), true);
                stringField(json, 3, "type", entry.type(), true);
                stringField(json, 3, "path", displayPath(projectRoot, entry.path()), true);
                intField(json, 3, "entries", entry.entries(), true);
                stringField(json, 3, "sha256", PackageEvidenceChecksums.sha256(entry.path()), false);
                indent(json, 2).append("}");
                if (index + 1 < entries.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static List<ArtifactEvidence> entries(
            PackagePlan plan,
            PackageResult result,
            List<PackageArtifact> artifacts) {
        Map<String, ProducedArtifact> produced = new LinkedHashMap<>();
        produced.put(
                "main",
                new ProducedArtifact(result.jarPath(), result.entryCount()));
        for (PackageArtifact artifact : artifacts.stream()
                .sorted(Comparator.comparing(PackageArtifact::classifier))
                .toList()) {
            if (produced.put(
                            artifact.classifier(),
                            new ProducedArtifact(
                                    artifact.path(),
                                    artifact.entryCount()))
                    != null) {
                throw new PackageException(
                        "Package produced artifact classifier `"
                                + artifact.classifier()
                                + "` more than once.");
            }
        }
        List<PackagePlanOutput> expected = plan.evidence().outputs().stream()
                .filter(PackagePlanOutput::publishArtifact)
                .toList();
        List<String> expectedClassifiers =
                expected.stream().map(PackagePlanOutput::kind).toList();
        if (!produced.keySet().equals(new java.util.LinkedHashSet<>(expectedClassifiers))) {
            throw new PackageException(
                    "Package artifacts do not match the current package plan. Expected "
                            + expectedClassifiers
                            + " but packaging produced "
                            + produced.keySet()
                            + ".");
        }
        return expected.stream()
                .map(output -> {
                    ProducedArtifact artifact = produced.get(output.kind());
                    Path path = artifact.path().toAbsolutePath().normalize();
                    if (!path.equals(output.path())) {
                        throw new PackageException(
                                "Package artifact `"
                                        + output.kind()
                                        + "` was written to "
                                        + path
                                        + " but the current package plan requires "
                                        + output.path()
                                        + ".");
                    }
                    return new ArtifactEvidence(
                            output.kind(),
                            output.artifactType(),
                            path,
                            artifact.entries());
                })
                .toList();
    }

    private record ArtifactEvidence(String classifier, String type, Path path, int entries) {
    }

    private record ProducedArtifact(Path path, int entries) {
    }
}
