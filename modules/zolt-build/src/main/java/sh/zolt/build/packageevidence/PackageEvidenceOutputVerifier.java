package sh.zolt.build.packageevidence;

import static sh.zolt.build.packageevidence.PackageEvidencePaths.display;
import static sh.zolt.build.packageevidence.PackageEvidencePaths.resolveConfined;

import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanOutput;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class PackageEvidenceOutputVerifier {
    private PackageEvidenceOutputVerifier() {
    }

    static void verify(
            Path root,
            PackagePlan currentPlan,
            PackageEvidenceManifest manifest,
            List<String> problems) {
        Map<String, PackageEvidenceOutput> evidenceOutputs =
                verifyOutputs(root, currentPlan, manifest, problems);
        verifyArtifacts(
                root,
                currentPlan,
                manifest,
                evidenceOutputs,
                problems);
    }

    private static Map<String, PackageEvidenceOutput> verifyOutputs(
            Path root,
            PackagePlan currentPlan,
            PackageEvidenceManifest manifest,
            List<String> problems) {
        Map<String, PackageEvidenceOutput> evidenceByKind =
                new LinkedHashMap<>();
        for (PackageEvidenceOutput output : manifest.outputs()) {
            if (evidenceByKind.put(output.kind(), output) != null) {
                problems.add(
                        "package evidence declares output kind `"
                                + output.kind()
                                + "` more than once");
            }
        }
        Map<String, PackagePlanOutput> expectedByKind = new LinkedHashMap<>();
        for (PackagePlanOutput output : currentPlan.evidence().outputs()) {
            if (expectedByKind.put(output.kind(), output) != null) {
                problems.add(
                        "current package plan declares output kind `"
                                + output.kind()
                                + "` more than once");
            }
        }
        if (!evidenceByKind.keySet().equals(expectedByKind.keySet())) {
            problems.add(
                    "package evidence output set "
                            + evidenceByKind.keySet()
                            + " does not match the current package plan "
                            + expectedByKind.keySet());
        }
        for (Map.Entry<String, PackagePlanOutput> entry :
                expectedByKind.entrySet()) {
            PackageEvidenceOutput evidence = evidenceByKind.get(entry.getKey());
            if (evidence == null) {
                continue;
            }
            PackagePlanOutput expected = entry.getValue();
            if (!expected.checksumKind().equals(evidence.checksumKind())) {
                problems.add(
                        "package output `"
                                + entry.getKey()
                                + "` checksum kind does not match the current package plan");
                continue;
            }
            Optional<Path> evidenced = resolveConfined(
                    root,
                    evidence.path(),
                    "package output `" + entry.getKey() + "`",
                    problems);
            if (evidenced.isEmpty()) {
                continue;
            }
            if (!evidenced.orElseThrow().equals(expected.path())) {
                problems.add(
                        "package output `"
                                + entry.getKey()
                                + "` evidence points to "
                                + display(root, evidenced.orElseThrow())
                                + " but the current plan requires "
                                + display(root, expected.path()));
                continue;
            }
            String actualSha256 = PackageEvidenceChecksums.outputSha256(
                    expected.path(),
                    expected.checksumKind());
            if ("missing".equals(actualSha256)) {
                problems.add(
                        "package output `"
                                + entry.getKey()
                                + "` is missing at "
                                + display(root, expected.path()));
            } else if (!actualSha256.equals(evidence.sha256())) {
                problems.add(
                        "package output `"
                                + entry.getKey()
                                + "` changed after packaging at "
                                + display(root, expected.path()));
            }
            if ("main".equals(entry.getKey())
                    && !evidence.sha256().equals(manifest.archiveSha256())) {
                problems.add(
                        "package archive checksum disagrees with the main output checksum");
            }
        }
        return evidenceByKind;
    }

    private static void verifyArtifacts(
            Path root,
            PackagePlan currentPlan,
            PackageEvidenceManifest manifest,
            Map<String, PackageEvidenceOutput> evidenceOutputs,
            List<String> problems) {
        Map<String, PackageEvidenceArtifact> evidenceByClassifier =
                new LinkedHashMap<>();
        for (PackageEvidenceArtifact artifact : manifest.artifacts()) {
            if (evidenceByClassifier.put(artifact.classifier(), artifact) != null) {
                problems.add(
                        "package evidence declares artifact classifier `"
                                + artifact.classifier()
                                + "` more than once");
            }
        }
        Map<String, PackagePlanOutput> expectedByClassifier =
                new LinkedHashMap<>();
        for (PackagePlanOutput output : currentPlan.evidence().outputs()) {
            if (output.publishArtifact()) {
                expectedByClassifier.put(output.kind(), output);
            }
        }
        if (!evidenceByClassifier.keySet().equals(expectedByClassifier.keySet())) {
            problems.add(
                    "package evidence artifact set "
                            + evidenceByClassifier.keySet()
                            + " does not match the current package plan "
                            + expectedByClassifier.keySet());
        }
        for (Map.Entry<String, PackagePlanOutput> entry :
                expectedByClassifier.entrySet()) {
            PackageEvidenceArtifact evidence =
                    evidenceByClassifier.get(entry.getKey());
            if (evidence == null) {
                continue;
            }
            PackagePlanOutput expected = entry.getValue();
            if (!expected.artifactType().equals(evidence.type())) {
                problems.add(
                        "package artifact `"
                                + entry.getKey()
                                + "` type `"
                                + evidence.type()
                                + "` does not match the current package plan `"
                                + expected.artifactType()
                                + "`");
            }
            Optional<Path> evidenced = resolveConfined(
                    root,
                    evidence.path(),
                    "package artifact `" + entry.getKey() + "`",
                    problems);
            if (evidenced.isPresent()
                    && !evidenced.orElseThrow().equals(expected.path())) {
                problems.add(
                        "package artifact `"
                                + entry.getKey()
                                + "` evidence points to "
                                + display(root, evidenced.orElseThrow())
                                + " but the current plan requires "
                                + display(root, expected.path()));
            }
            PackageEvidenceOutput output = evidenceOutputs.get(entry.getKey());
            if (output == null) {
                problems.add(
                        "package artifact `"
                                + entry.getKey()
                                + "` is absent from package outputs");
            } else if (!output.sha256().equals(evidence.sha256())) {
                problems.add(
                        "package artifact `"
                                + entry.getKey()
                                + "` checksum disagrees with its package output");
            }
        }
    }
}
