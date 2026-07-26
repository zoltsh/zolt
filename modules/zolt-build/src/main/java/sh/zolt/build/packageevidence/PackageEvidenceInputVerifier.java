package sh.zolt.build.packageevidence;

import static sh.zolt.build.packageevidence.PackageEvidencePaths.display;
import static sh.zolt.build.packageevidence.PackageEvidencePaths.resolveConfined;

import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanLiveInput;
import sh.zolt.build.packageplan.PackagePlanMaterializedInput;
import sh.zolt.build.packageplan.PackagePlanWorkspaceInput;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class PackageEvidenceInputVerifier {
    private PackageEvidenceInputVerifier() {
    }

    static void verify(
            Path root,
            PackagePlan currentPlan,
            PackageEvidenceManifest manifest,
            List<String> problems) {
        verifyLiveInputs(currentPlan, manifest, problems);
        verifyWorkspaceInputs(currentPlan, manifest, problems);
        verifyMaterializedInputs(root, currentPlan, manifest, problems);
    }

    private static void verifyLiveInputs(
            PackagePlan currentPlan,
            PackageEvidenceManifest manifest,
            List<String> problems) {
        Map<String, PackageEvidenceLiveInput> evidenceByKind =
                uniqueLiveInputs(manifest.supplementalInputs(), problems);
        Map<String, PackagePlanLiveInput> expectedByKind = new LinkedHashMap<>();
        for (PackagePlanLiveInput input :
                currentPlan.evidence().supplementalInputs()) {
            if (expectedByKind.put(input.kind(), input) != null) {
                problems.add(
                        "current package plan declares supplemental input `"
                                + input.kind()
                                + "` more than once");
            }
        }
        if (!evidenceByKind.keySet().equals(expectedByKind.keySet())) {
            problems.add(
                    "package evidence supplemental input set "
                            + evidenceByKind.keySet()
                            + " does not match the current package plan "
                            + expectedByKind.keySet());
        }
        for (Map.Entry<String, PackagePlanLiveInput> entry :
                expectedByKind.entrySet()) {
            PackageEvidenceLiveInput evidence = evidenceByKind.get(entry.getKey());
            if (evidence != null
                    && !entry.getValue()
                            .fingerprint()
                            .equals(evidence.fingerprint())) {
                problems.add(
                        "supplemental package input `"
                                + entry.getKey()
                                + "` changed after packaging");
            }
        }
    }

    private static Map<String, PackageEvidenceLiveInput> uniqueLiveInputs(
            List<PackageEvidenceLiveInput> inputs,
            List<String> problems) {
        Map<String, PackageEvidenceLiveInput> byKind = new LinkedHashMap<>();
        for (PackageEvidenceLiveInput input : inputs) {
            if (byKind.put(input.kind(), input) != null) {
                problems.add(
                        "package evidence declares supplemental input `"
                                + input.kind()
                                + "` more than once");
            }
        }
        return byKind;
    }

    private static void verifyWorkspaceInputs(
            PackagePlan currentPlan,
            PackageEvidenceManifest manifest,
            List<String> problems) {
        Map<String, PackageEvidenceWorkspaceInput> evidenceByCoordinate =
                new LinkedHashMap<>();
        for (PackageEvidenceWorkspaceInput input : manifest.workspaceInputs()) {
            if (evidenceByCoordinate.put(input.coordinate(), input) != null) {
                problems.add(
                        "package evidence declares workspace input `"
                                + input.coordinate()
                                + "` more than once");
            }
        }
        Map<String, PackagePlanWorkspaceInput> expectedByCoordinate =
                new LinkedHashMap<>();
        for (PackagePlanWorkspaceInput input :
                currentPlan.evidence().workspaceInputs()) {
            expectedByCoordinate.put(input.coordinate(), input);
        }
        if (!evidenceByCoordinate.keySet().equals(expectedByCoordinate.keySet())) {
            problems.add(
                    "package evidence workspace input set "
                            + evidenceByCoordinate.keySet()
                            + " does not match the current package plan "
                            + expectedByCoordinate.keySet());
        }
        for (Map.Entry<String, PackagePlanWorkspaceInput> entry :
                expectedByCoordinate.entrySet()) {
            PackageEvidenceWorkspaceInput evidence =
                    evidenceByCoordinate.get(entry.getKey());
            if (evidence == null) {
                continue;
            }
            PackagePlanWorkspaceInput expected = entry.getValue();
            if (!expected.identity().equals(evidence.identity())) {
                problems.add(
                        "workspace package input `"
                                + entry.getKey()
                                + "` identity does not match the current package plan");
            }
            if (!expected.fingerprint().equals(evidence.fingerprint())) {
                problems.add(
                        "workspace package input `"
                                + entry.getKey()
                                + "` changed after packaging");
            }
        }
    }

    private static void verifyMaterializedInputs(
            Path root,
            PackagePlan currentPlan,
            PackageEvidenceManifest manifest,
            List<String> problems) {
        Map<String, PackageEvidenceMaterializedInput> evidenceByCoordinate =
                new LinkedHashMap<>();
        for (PackageEvidenceMaterializedInput input :
                manifest.materializedInputs()) {
            if (evidenceByCoordinate.put(input.coordinate(), input) != null) {
                problems.add(
                        "package evidence declares materialized input `"
                                + input.coordinate()
                                + "` more than once");
            }
        }
        Map<String, PackagePlanMaterializedInput> expectedByCoordinate =
                new LinkedHashMap<>();
        for (PackagePlanMaterializedInput input :
                currentPlan.evidence().materializedInputs()) {
            expectedByCoordinate.put(input.coordinate(), input);
        }
        if (!evidenceByCoordinate.keySet().equals(expectedByCoordinate.keySet())) {
            problems.add(
                    "package evidence materialized input set "
                            + evidenceByCoordinate.keySet()
                            + " does not match the current package plan "
                            + expectedByCoordinate.keySet());
        }
        for (Map.Entry<String, PackagePlanMaterializedInput> entry :
                expectedByCoordinate.entrySet()) {
            PackageEvidenceMaterializedInput evidence =
                    evidenceByCoordinate.get(entry.getKey());
            if (evidence == null) {
                continue;
            }
            PackagePlanMaterializedInput expected = entry.getValue();
            if (!expected.sourceIdentity().equals(evidence.sourceIdentity())) {
                problems.add(
                        "materialized package input `"
                                + entry.getKey()
                                + "` source identity does not match the current package plan");
            }
            if (!expected.sourceFingerprint().equals(evidence.sourceFingerprint())) {
                problems.add(
                        "materialized package input source `"
                                + entry.getKey()
                                + "` changed after packaging");
            }
            Optional<Path> evidencedJar = resolveConfined(
                    root,
                    evidence.jar(),
                    "materialized package input `" + entry.getKey() + "`",
                    problems);
            if (evidencedJar.isEmpty()) {
                continue;
            }
            if (!evidencedJar.orElseThrow().equals(expected.jarPath())) {
                problems.add(
                        "materialized package input `"
                                + entry.getKey()
                                + "` evidence points to "
                                + display(root, evidencedJar.orElseThrow())
                                + " but the current plan requires "
                                + display(root, expected.jarPath()));
                continue;
            }
            String currentJar = PackageEvidenceChecksums.fileSha256(
                    expected.jarPath());
            if ("missing".equals(currentJar)) {
                problems.add(
                        "materialized package input `"
                                + entry.getKey()
                                + "` is missing at "
                                + display(root, expected.jarPath()));
            } else if (!currentJar.equals(evidence.sha256())) {
                problems.add(
                        "materialized package input `"
                                + entry.getKey()
                                + "` changed after packaging at "
                                + display(root, expected.jarPath()));
            }
        }
    }
}
