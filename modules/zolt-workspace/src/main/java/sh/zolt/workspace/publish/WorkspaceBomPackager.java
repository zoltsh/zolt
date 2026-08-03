package sh.zolt.workspace.publish;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageevidence.PackageEvidenceVerification;
import sh.zolt.build.packageevidence.PackageEvidenceVerifier;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packaging.PackageArchiveWriter;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishPomGenerator;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Packages a BOM member: no compile, no jar. Generates the {@code <dependencyManagement>} POM into
 * {@code target/publish/<name>-<version>.pom} and records package evidence with the POM's sha256, so
 * publish (and users) have a durable, checksummed artifact.
 */
public final class WorkspaceBomPackager {
    private final WorkspaceBomFamily family = new WorkspaceBomFamily();
    private final PublishPomGenerator pomGenerator = new PublishPomGenerator();
    private final PackagePlanService packagePlanService;
    private final PackageEvidenceManifestWriter evidenceManifestWriter;
    private final PackageEvidenceVerifier evidenceVerifier = new PackageEvidenceVerifier();

    public WorkspaceBomPackager() {
        this(new PackagePlanService());
    }

    public WorkspaceBomPackager(PackagePlanService packagePlanService) {
        this(packagePlanService, new PackageEvidenceManifestWriter());
    }

    WorkspaceBomPackager(
            PackagePlanService packagePlanService,
            PackageEvidenceManifestWriter evidenceManifestWriter) {
        this.packagePlanService = packagePlanService;
        this.evidenceManifestWriter = evidenceManifestWriter;
    }

    public PackageResult packageBom(
            WorkspaceMember bomMember, Workspace workspace, ZoltLockfile aggregatedLock, BuildResult buildResult) {
        ZoltLockfile familyLock = family.familyLock(workspace, aggregatedLock, bomMember);
        return write(bomMember.directory(), bomMember.config(), familyLock, buildResult);
    }

    /** Writes the BOM POM + evidence for a standalone (non-workspace) BOM with no family members. */
    public PackageResult packageStandaloneBom(Path projectDirectory, ProjectConfig config, BuildResult buildResult) {
        return write(projectDirectory, config, new ZoltLockfile(1, List.of(), List.of()), buildResult);
    }

    private PackageResult write(
            Path projectDirectory, ProjectConfig config, ZoltLockfile familyLock, BuildResult buildResult) {
        String artifactBase = config.project().name() + "-" + config.project().version();
        Path publishDirectory = projectDirectory.resolve(config.build().outputRoot()).resolve("publish");
        Path pomPath = publishDirectory.resolve(artifactBase + ".pom");
        PackagePlan plan =
                packagePlanService.plan(projectDirectory, config, familyLock);
        Optional<PackageResult> reused = reuse(projectDirectory, buildResult, plan);
        if (reused.isPresent()) {
            return reused.orElseThrow();
        }
        try {
            Files.createDirectories(publishDirectory);
            PackageArchiveWriter.writeStringAtomically(
                    pomPath,
                    pomGenerator.generate(config, familyLock),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new sh.zolt.workspace.WorkspaceConfigException(
                    "Could not write BOM package artifact at " + pomPath + ": " + exception.getMessage());
        }
        PackageResult result = new PackageResult(
                buildResult,
                PackageMode.BOM,
                pomPath,
                Optional.empty(),
                Optional.empty(),
                familyLock.packages().size()
                        + config.packageSettings().bom().versions().size()
                        + config.packageSettings().bom().imports().size(),
                false,
                "pom",
                List.of(),
                List.of());
        Path evidencePath = evidenceManifestWriter.write(
                projectDirectory,
                config,
                plan,
                result,
                List.of());
        return result.withArtifactsAndEvidence(
                List.of(),
                Optional.of(evidencePath));
    }

    private Optional<PackageResult> reuse(
            Path projectDirectory,
            BuildResult buildResult,
            PackagePlan plan) {
        Path evidencePath =
                PackageEvidenceManifestWriter.evidenceManifestPath(plan.archivePath());
        if (!Files.isRegularFile(evidencePath)) {
            return Optional.empty();
        }
        PackageEvidenceVerification verification =
                evidenceVerifier.verify(projectDirectory, plan, evidencePath);
        if (!verification.valid()) {
            return Optional.empty();
        }
        var evidence = verification.manifest().orElseThrow();
        var main = evidence.artifacts().stream()
                .filter(artifact -> "main".equals(artifact.classifier()))
                .findFirst();
        if (main.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PackageResult(
                buildResult,
                PackageMode.BOM,
                plan.archivePath(),
                Optional.empty(),
                Optional.of(evidencePath),
                main.orElseThrow().entries(),
                false,
                plan.applicationLayout(),
                List.of(),
                evidence.uberMergeDecisions(),
                List.of(),
                true));
    }
}
