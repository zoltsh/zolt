package sh.zolt.build.packageevidence;

import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.booleanField;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.displayPath;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.indent;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.stringArrayField;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.stringField;

import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackagePlanLiveInput;
import sh.zolt.build.packageplan.PackagePlanMaterializedInput;
import sh.zolt.build.packageplan.PackagePlanDependency;
import sh.zolt.build.packageplan.PackagePlanWorkspaceInput;
import sh.zolt.build.packaging.PackageMaterializedInput;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PackageEvidenceInputWriter {
    private PackageEvidenceInputWriter() {
    }

    static void writeMaterializedInputs(
            StringBuilder json,
            Path projectRoot,
            List<PackagePlanMaterializedInput> expectedInputs,
            List<PackageMaterializedInput> inputs) {
        Map<String, PackageMaterializedInput> actualByCoordinate =
                new LinkedHashMap<>();
        for (PackageMaterializedInput input : inputs) {
            if (actualByCoordinate.put(input.coordinate(), input) != null) {
                throw new PackageException(
                        "Package materialized workspace input `"
                                + input.coordinate()
                                + "` more than once.");
            }
        }
        List<String> expectedCoordinates = expectedInputs.stream()
                .map(PackagePlanMaterializedInput::coordinate)
                .toList();
        if (!actualByCoordinate.keySet().equals(
                new java.util.LinkedHashSet<>(expectedCoordinates))) {
            throw new PackageException(
                    "Materialized package inputs do not match the current package plan. Expected "
                            + expectedCoordinates
                            + " but packaging produced "
                            + actualByCoordinate.keySet()
                            + ".");
        }
        json.append("  \"materializedInputs\": [");
        if (!expectedInputs.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < expectedInputs.size(); index++) {
                PackagePlanMaterializedInput expected = expectedInputs.get(index);
                PackageMaterializedInput input =
                        actualByCoordinate.get(expected.coordinate());
                validateMaterializedInput(expected, input);
                indent(json, 2).append("{\n");
                stringField(json, 3, "coordinate", input.coordinate(), true);
                stringField(
                        json,
                        3,
                        "sourceIdentity",
                        expected.sourceIdentity(),
                        true);
                stringField(json, 3, "sourceFingerprint", input.sourceFingerprint(), true);
                stringField(
                        json,
                        3,
                        "jar",
                        displayPath(projectRoot, input.jarPath()),
                        true);
                stringField(json, 3, "sha256", input.sha256(), false);
                indent(json, 2).append("}");
                if (index + 1 < expectedInputs.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    static void writeSupplementalInputs(
            StringBuilder json,
            List<PackagePlanLiveInput> inputs) {
        indent(json, 1).append("\"supplementalInputs\": [");
        if (!inputs.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < inputs.size(); index++) {
                PackagePlanLiveInput input = inputs.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "kind", input.kind(), true);
                stringField(json, 3, "fingerprint", input.fingerprint(), false);
                indent(json, 2).append("}");
                if (index + 1 < inputs.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    static void writeWorkspaceInputs(
            StringBuilder json,
            List<PackagePlanWorkspaceInput> inputs) {
        indent(json, 1).append("\"workspaceInputs\": [");
        if (!inputs.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < inputs.size(); index++) {
                PackagePlanWorkspaceInput input = inputs.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "coordinate", input.coordinate(), true);
                stringField(json, 3, "identity", input.identity(), true);
                stringField(json, 3, "fingerprint", input.fingerprint(), false);
                indent(json, 2).append("}");
                if (index + 1 < inputs.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static void validateMaterializedInput(
            PackagePlanMaterializedInput expected,
            PackageMaterializedInput actual) {
        if (!actual.sourceDirectory()
                .toAbsolutePath()
                .normalize()
                .equals(expected.sourceDirectory())) {
            throw new PackageException(
                    "Materialized package input `"
                            + expected.coordinate()
                            + "` came from "
                            + actual.sourceDirectory()
                            + " but the current plan requires "
                            + expected.sourceDirectory()
                            + ".");
        }
        if (!actual.jarPath()
                .toAbsolutePath()
                .normalize()
                .equals(expected.jarPath())) {
            throw new PackageException(
                    "Materialized package input `"
                            + expected.coordinate()
                            + "` was staged at "
                            + actual.jarPath()
                            + " but the current plan requires "
                            + expected.jarPath()
                            + ".");
        }
        if (!actual.sourceFingerprint().equals(expected.sourceFingerprint())) {
            throw new PackageException(
                    "Materialized package input `"
                            + expected.coordinate()
                            + "` source bytes changed while packaging.");
        }
    }

    static void writeDependencies(
            StringBuilder json,
            List<PackagePlanDependency> dependencies) {
        indent(json, 1).append("\"dependencies\": [");
        if (!dependencies.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < dependencies.size(); index++) {
                PackagePlanDependency dependency = dependencies.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "coordinate", dependency.coordinate(), true);
                stringField(json, 3, "version", dependency.version(), true);
                stringField(json, 3, "scope", dependency.scope().lockfileName(), true);
                stringArrayField(json, 3, "lanes", dependency.lanes(), true);
                booleanField(json, 3, "packageDefault", dependency.packageDefault(), true);
                stringField(json, 3, "laneDisposition", dependency.laneDisposition(), true);
                stringField(json, 3, "disposition", dependency.disposition(), true);
                stringField(json, 3, "rule", dependency.ruleName(), true);
                stringField(json, 3, "location", dependency.location(), true);
                stringField(json, 3, "reason", dependency.reason(), true);
                stringArrayField(json, 3, "policies", dependency.policies(), false);
                indent(json, 2).append("}");
                if (index + 1 < dependencies.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }
}
