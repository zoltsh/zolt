package sh.zolt.build.packageevidence;

import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.booleanField;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.displayPath;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.indent;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.stringArrayField;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.stringField;

import sh.zolt.build.packageplan.PackagePlanDependency;
import sh.zolt.build.packaging.PackageMaterializedInput;
import java.nio.file.Path;
import java.util.List;

final class PackageEvidenceInputWriter {
    private PackageEvidenceInputWriter() {
    }

    static void writeMaterializedInputs(
            StringBuilder json,
            Path projectRoot,
            List<PackageMaterializedInput> inputs) {
        json.append("  \"materializedInputs\": [");
        if (!inputs.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < inputs.size(); index++) {
                PackageMaterializedInput input = inputs.get(index);
                indent(json, 2).append("{\n");
                stringField(json, 3, "coordinate", input.coordinate(), true);
                stringField(
                        json,
                        3,
                        "sourceDirectory",
                        displayPath(projectRoot, input.sourceDirectory()),
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
                if (index + 1 < inputs.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
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
