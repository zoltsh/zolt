package sh.zolt.build.packageevidence;

import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.displayPath;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.indent;
import static sh.zolt.build.packageevidence.PackageEvidenceJsonFields.stringField;

import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanOutput;
import sh.zolt.build.packaging.PackageArtifact;
import sh.zolt.build.packaging.PackageResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PackageEvidenceOutputWriter {
    private PackageEvidenceOutputWriter() {
    }

    static void write(
            StringBuilder json,
            Path projectRoot,
            PackagePlan plan,
            PackageResult result,
            List<PackageArtifact> artifacts,
            PackageArchiveDigests digests) {
        Map<String, Path> actual = new LinkedHashMap<>();
        actual.put("main", result.jarPath());
        result.runtimeClasspathPath().ifPresent(
                path -> actual.put("runtime-classpath", path));
        if (result.mode() == sh.zolt.project.PackageMode.QUARKUS) {
            actual.put("quarkus-layout", result.jarPath().getParent());
        }
        artifacts.stream()
                .sorted(java.util.Comparator.comparing(PackageArtifact::classifier))
                .forEach(artifact -> actual.put(
                        artifact.classifier(),
                        artifact.path()));
        List<PackagePlanOutput> expected = plan.evidence().outputs();
        List<String> expectedKeys = expected.stream()
                .map(PackagePlanOutput::kind)
                .toList();
        if (!actual.keySet().equals(new java.util.LinkedHashSet<>(expectedKeys))) {
            throw new PackageException(
                    "Package outputs do not match the current package plan. Expected "
                            + expectedKeys
                            + " but packaging produced "
                            + new ArrayList<>(actual.keySet())
                            + ".");
        }
        for (PackagePlanOutput output : expected) {
            Path actualPath = actual.get(output.kind())
                    .toAbsolutePath()
                    .normalize();
            if (!actualPath.equals(output.path())) {
                throw new PackageException(
                        "Package output `"
                                + output.kind()
                                + "` was written to "
                                + displayPath(projectRoot, actualPath)
                                + " but the current package plan requires "
                                + displayPath(projectRoot, output.path())
                                + ".");
            }
        }

        indent(json, 1).append("\"outputs\": [\n");
        for (int index = 0; index < expected.size(); index++) {
            PackagePlanOutput output = expected.get(index);
            indent(json, 2).append("{\n");
            stringField(json, 3, "kind", output.kind(), true);
            stringField(
                    json,
                    3,
                    "path",
                    displayPath(projectRoot, output.path()),
                    true);
            stringField(
                    json,
                    3,
                    "checksumKind",
                    output.checksumKind(),
                    true);
            stringField(
                    json,
                    3,
                    "sha256",
                    outputSha256(digests, output.path(), output.checksumKind()),
                    false);
            indent(json, 2).append("}");
            if (index + 1 < expected.size()) {
                json.append(',');
            }
            json.append('\n');
        }
        indent(json, 1).append("]");
    }

    private static String outputSha256(
            PackageArchiveDigests digests,
            Path path,
            String checksumKind) {
        if ("tree".equals(checksumKind)) {
            return PackageEvidenceChecksums.outputSha256(path, checksumKind);
        }
        return digests.sha256(path);
    }
}
