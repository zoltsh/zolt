package sh.zolt.publish;

import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class PublishDryRunBomPlanner {
    private final PublishDryRunArtifactEvidencePlanner artifactEvidencePlanner;
    private final MavenRepositoryPathBuilder repositoryPathBuilder;

    PublishDryRunBomPlanner(
            PublishDryRunArtifactEvidencePlanner artifactEvidencePlanner,
            MavenRepositoryPathBuilder repositoryPathBuilder) {
        this.artifactEvidencePlanner = artifactEvidencePlanner;
        this.repositoryPathBuilder = repositoryPathBuilder;
    }

    PublishDryRunPlan plan(
            Path root,
            ProjectConfig config,
            PackagePlan currentPlan,
            String versionKind,
            String displayRepositoryId,
            String displayRepositoryUrl,
            Coordinate coordinate,
            List<String> blockers) {
        // A BOM has no archive: its artifact is the dependency-management POM. An SBOM is
        // deliberately not attached because a BOM has no resolved runtime graph.
        Path pomPath = currentPlan.archivePath();
        Path evidencePath =
                PackageEvidenceManifestWriter.evidenceManifestPath(pomPath);
        PublishDryRunArtifactEvidence artifactEvidence =
                artifactEvidencePlanner.plan(
                        root,
                        coordinate,
                        currentPlan,
                        pomPath,
                        evidencePath,
                        blockers);
        String pomSha256 = artifactEvidence.artifactSha256();
        String pomUploadPath = repositoryPathBuilder.pomPath(coordinate);
        List<PublishChecksumSidecar> checksumSidecars = new ArrayList<>();
        if (Files.isRegularFile(pomPath)) {
            for (PublishChecksum.Sidecar sidecar : PublishChecksum.sidecars(pomPath)) {
                checksumSidecars.add(new PublishChecksumSidecar(
                        "pom",
                        sidecar.extension(),
                        pomUploadPath + "." + sidecar.extension(),
                        sidecar.value()));
            }
        }
        Path pomDisplay =
                PublishDryRunArtifactEvidencePlanner.display(root, pomPath);
        return new PublishDryRunPlan(
                config.project().group()
                        + ":"
                        + config.project().name()
                        + ":"
                        + config.project().version(),
                versionKind,
                displayRepositoryId,
                displayRepositoryUrl,
                "bom",
                pomDisplay,
                pomSha256,
                "",
                List.of(),
                PublishDryRunArtifactEvidencePlanner.display(root, evidencePath),
                pomDisplay,
                pomSha256,
                pomUploadPath,
                List.copyOf(checksumSidecars),
                "",
                blockers,
                true);
    }
}
