package sh.zolt.workspace.publish;

import sh.zolt.build.BuildResult;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.build.packaging.PackageResult;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.publish.PublishPomGenerator;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
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
        try {
            Files.createDirectories(publishDirectory);
            Files.writeString(pomPath, pomGenerator.generate(config, familyLock));
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
        PackagePlan plan =
                packagePlanService.plan(projectDirectory, config, familyLock);
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
}
