package sh.zolt.publish;

import sh.zolt.build.packageevidence.PackageEvidenceArtifact;
import sh.zolt.build.packageevidence.PackageEvidenceManifest;
import sh.zolt.build.packageevidence.PackageEvidenceManifestReader;
import sh.zolt.build.packageevidence.PackageEvidenceVerification;
import sh.zolt.build.packageevidence.PackageEvidenceVerifier;
import sh.zolt.build.PackageException;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanOutput;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class PublishDryRunArtifactEvidencePlanner {
    private final PackageEvidenceVerifier evidenceVerifier;
    private final MavenRepositoryPathBuilder repositoryPathBuilder;

    PublishDryRunArtifactEvidencePlanner(
            PackageEvidenceManifestReader evidenceManifestReader,
            MavenRepositoryPathBuilder repositoryPathBuilder) {
        this.evidenceVerifier =
                new PackageEvidenceVerifier(evidenceManifestReader);
        this.repositoryPathBuilder = repositoryPathBuilder;
    }

    PublishDryRunArtifactEvidence plan(
            Path root,
            Coordinate coordinate,
            PackagePlan currentPlan,
            Path artifactPath,
            Path evidencePath,
            List<String> blockers) {
        if (!Files.isRegularFile(artifactPath)) {
            blockers.add("missing artifact: run `zolt package` to create " + displayPath(root, artifactPath));
            return empty();
        }
        if (!Files.isRegularFile(evidencePath)) {
            blockers.add("missing package evidence: run `zolt package` to create " + displayPath(root, evidencePath));
            return empty();
        }
        try {
            PackageEvidenceVerification verification =
                    evidenceVerifier.verify(
                            root,
                            currentPlan,
                            evidencePath);
            for (String problem : verification.problems()) {
                blockers.add(
                        "stale package evidence: "
                                + problem
                                + ". Run `zolt package` to refresh "
                                + displayPath(root, evidencePath));
            }
            PackageEvidenceManifest evidence =
                    verification.manifest().orElse(null);
            if (evidence == null) {
                return empty();
            }
            return new PublishDryRunArtifactEvidence(
                    evidence.archiveSha256(),
                    supplementalArtifacts(
                            root,
                            coordinate,
                            currentPlan,
                            evidence,
                            evidencePath,
                            blockers));
        } catch (PackageException exception) {
            blockers.add("invalid package evidence: " + exception.getMessage());
            return empty();
        }
    }

    private List<PublishArtifactPlan> supplementalArtifacts(
            Path root,
            Coordinate coordinate,
            PackagePlan currentPlan,
            PackageEvidenceManifest evidence,
            Path evidencePath,
            List<String> blockers) {
        List<PublishArtifactPlan> artifacts = new ArrayList<>();
        Map<String, PackageEvidenceArtifact> evidenceByClassifier =
                new LinkedHashMap<>();
        for (PackageEvidenceArtifact artifact : evidence.artifacts()) {
            evidenceByClassifier.put(artifact.classifier(), artifact);
        }
        for (PackagePlanOutput output : currentPlan.evidence().outputs()) {
            if (!output.publishArtifact() || "main".equals(output.kind())) {
                continue;
            }
            PackageEvidenceArtifact artifact =
                    evidenceByClassifier.get(output.kind());
            if (artifact == null) {
                continue;
            }
            Path artifactPath = output.path();
            String uploadPath = repositoryPathBuilder.artifactPath(new ArtifactDescriptor(
                    coordinate,
                    Optional.of(output.kind()),
                    extension(artifactPath)));
            if (!Files.isRegularFile(artifactPath)) {
                blockers.add("missing supplemental artifact: run `zolt package` to create "
                        + displayPath(root, artifactPath));
                continue;
            }
            String actualSha256 = PublishChecksum.sha256(artifactPath);
            if (!actualSha256.equals(artifact.sha256())) {
                blockers.add("stale supplemental package evidence: run `zolt package` to refresh "
                        + displayPath(root, evidencePath));
            }
            artifacts.add(new PublishArtifactPlan(
                    output.kind(),
                    Optional.of(output.kind()),
                    display(root, artifactPath),
                    artifact.sha256(),
                    uploadPath));
        }
        return List.copyOf(artifacts);
    }

    static String extension(Path artifactPath) {
        String fileName = artifactPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            throw new PublishException("Could not determine publish artifact extension from `" + fileName + "`.");
        }
        return fileName.substring(dot + 1);
    }

    static Path display(Path root, Path path) {
        return Path.of(displayPath(root, path));
    }

    static String displayPath(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            return root.relativize(normalized).toString();
        }
        return normalized.toString();
    }

    private static PublishDryRunArtifactEvidence empty() {
        return new PublishDryRunArtifactEvidence("", List.of());
    }
}
