package sh.zolt.build.packageevidence;

import static sh.zolt.build.packageevidence.PackageEvidencePaths.display;
import static sh.zolt.build.packageevidence.PackageEvidencePaths.resolveConfined;

import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackagePlan;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
        verifySchema(manifest, problems);
        verifyArchive(root, currentPlan, manifest, problems);
        compareFingerprint(
                "package inputs",
                currentPlan.evidence().inputFingerprint(),
                manifest.inputFingerprint(),
                problems);
        compareFingerprint(
                "build inputs",
                currentPlan.evidence().buildInputFingerprint(),
                manifest.buildInputFingerprint(),
                problems);
        compareFingerprint(
                "application output",
                currentPlan.evidence().applicationOutputFingerprint(),
                manifest.applicationOutputFingerprint(),
                problems);
        if (!currentPlan.dependencies().equals(manifest.dependencies())) {
            problems.add(
                    "package evidence dependency dispositions do not match the current package plan");
        }
        PackageEvidenceInputVerifier.verify(
                root,
                currentPlan,
                manifest,
                problems);
        PackageEvidenceOutputVerifier.verify(
                root,
                currentPlan,
                manifest,
                problems);
        return new PackageEvidenceVerification(
                List.copyOf(problems),
                Optional.of(manifest));
    }

    private static void verifySchema(
            PackageEvidenceManifest manifest,
            List<String> problems) {
        if (!PackageEvidenceManifestWriter.SCHEMA.equals(manifest.schema())) {
            problems.add(
                    "package evidence schema `"
                            + manifest.schema()
                            + "` is stale; expected `"
                            + PackageEvidenceManifestWriter.SCHEMA
                            + "`");
        }
    }

    private static void verifyArchive(
            Path root,
            PackagePlan currentPlan,
            PackageEvidenceManifest manifest,
            List<String> problems) {
        resolveConfined(root, manifest.archive(), "package archive", problems)
                .ifPresent(evidenceArchive -> {
                    if (!evidenceArchive.equals(currentPlan.archivePath())) {
                        problems.add(
                                "package evidence describes "
                                        + display(root, evidenceArchive)
                                        + " but the current package plan selects "
                                        + display(root, currentPlan.archivePath()));
                    }
                });
    }

    private static void compareFingerprint(
            String description,
            String expected,
            String evidence,
            List<String> problems) {
        if (!expected.equals(evidence)) {
            problems.add(
                    description
                            + " changed after the artifact was packaged"
                            + " (current "
                            + expected
                            + ", evidence "
                            + displayFingerprint(evidence)
                            + ")");
        }
    }

    private static String displayFingerprint(String fingerprint) {
        return fingerprint == null || fingerprint.isBlank()
                ? "missing"
                : fingerprint;
    }
}
