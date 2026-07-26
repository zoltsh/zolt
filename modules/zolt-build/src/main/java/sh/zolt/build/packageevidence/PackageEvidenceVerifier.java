package sh.zolt.build.packageevidence;

import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanOutput;
import sh.zolt.build.packaging.PackageRuntimeJarMaterializer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The shared acceptance contract for package quality and publish preflight.
 */
public final class PackageEvidenceVerifier {
    private final PackageEvidenceManifestReader reader;

    public PackageEvidenceVerifier() {
        this(new PackageEvidenceManifestReader());
    }

    public PackageEvidenceVerifier(PackageEvidenceManifestReader reader) {
        this.reader = reader;
    }

    public PackageEvidenceVerification verify(
            Path projectRoot,
            PackagePlan currentPlan,
            Path manifestPath) {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<String> problems = new ArrayList<>();
        PackageEvidenceManifest manifest;
        try {
            manifest = reader.read(manifestPath);
        } catch (PackageException exception) {
            return new PackageEvidenceVerification(
                    List.of("invalid package evidence: " + exception.getMessage()),
                    Optional.empty());
        }
        if (!PackageEvidenceManifestWriter.SCHEMA.equals(manifest.schema())) {
            problems.add(
                    "package evidence schema `"
                            + manifest.schema()
                            + "` is stale; expected `"
                            + PackageEvidenceManifestWriter.SCHEMA
                            + "`");
        }
        Path evidenceArchive = resolve(root, manifest.archive());
        if (!evidenceArchive.equals(currentPlan.archivePath())) {
            problems.add(
                    "package evidence describes "
                            + display(root, evidenceArchive)
                            + " but the current package plan selects "
                            + display(root, currentPlan.archivePath()));
        }
        if (!currentPlan.evidence().inputFingerprint()
                .equals(manifest.inputFingerprint())) {
            problems.add(
                    "package inputs changed after the artifact was packaged"
                            + " (current "
                            + currentPlan.evidence().inputFingerprint()
                            + ", evidence "
                            + displayFingerprint(manifest.inputFingerprint())
                            + ")");
        }
        if (!currentPlan.dependencies().equals(
                manifest.dependencies())) {
            problems.add(
                    "package evidence dependency dispositions do not match the current package plan");
        }
        verifyOutputs(root, currentPlan, manifest, problems);
        verifyMaterializedInputs(root, manifest, problems);
        return new PackageEvidenceVerification(
                List.copyOf(problems),
                Optional.of(manifest));
    }

    private static void verifyOutputs(
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
            expectedByKind.put(output.kind(), output);
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
            Path expected = entry.getValue().path();
            Path evidenced = resolve(root, evidence.path());
            if (!evidenced.equals(expected)) {
                problems.add(
                        "package output `"
                                + entry.getKey()
                                + "` evidence points to "
                                + display(root, evidenced)
                                + " but the current plan requires "
                                + display(root, expected));
                continue;
            }
            String actualSha256 =
                    PackageEvidenceChecksums.fileSha256(expected);
            if ("missing".equals(actualSha256)) {
                problems.add(
                        "package output `"
                                + entry.getKey()
                                + "` is missing at "
                                + display(root, expected));
            } else if (!actualSha256.equals(evidence.sha256())) {
                problems.add(
                        "package output `"
                                + entry.getKey()
                                + "` changed after packaging at "
                                + display(root, expected));
            }
            if ("main".equals(entry.getKey())
                    && !evidence.sha256().equals(manifest.archiveSha256())) {
                problems.add(
                        "package archive checksum disagrees with the main output checksum");
            }
        }
    }

    private static void verifyMaterializedInputs(
            Path root,
            PackageEvidenceManifest manifest,
            List<String> problems) {
        for (PackageEvidenceMaterializedInput input :
                manifest.materializedInputs()) {
            Path sourceDirectory = resolve(root, input.sourceDirectory());
            String currentSource =
                    PackageRuntimeJarMaterializer.directoryFingerprint(
                            sourceDirectory);
            if (!currentSource.equals(input.sourceFingerprint())) {
                problems.add(
                        "materialized package input source `"
                                + input.coordinate()
                                + "` changed after packaging at "
                                + display(root, sourceDirectory));
            }
            Path jar = resolve(root, input.jar());
            String currentJar = PackageEvidenceChecksums.fileSha256(jar);
            if ("missing".equals(currentJar)) {
                problems.add(
                        "materialized package input `"
                                + input.coordinate()
                                + "` is missing at "
                                + display(root, jar));
            } else if (!currentJar.equals(input.sha256())) {
                problems.add(
                        "materialized package input `"
                                + input.coordinate()
                                + "` changed after packaging at "
                                + display(root, jar));
            }
        }
    }

    private static Path resolve(Path root, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : root.resolve(path))
                .toAbsolutePath()
                .normalize();
    }

    private static String display(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                ? root.relativize(normalized).toString()
                : normalized.toString();
    }

    private static String displayFingerprint(String fingerprint) {
        return fingerprint == null || fingerprint.isBlank()
                ? "missing"
                : fingerprint;
    }
}
