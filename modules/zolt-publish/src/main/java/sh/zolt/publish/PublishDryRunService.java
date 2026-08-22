package sh.zolt.publish;

import sh.zolt.lockfile.ProjectLockfile;
import sh.zolt.build.packageevidence.PackageEvidenceManifestReader;
import sh.zolt.build.packageevidence.PackageEvidenceManifestWriter;
import sh.zolt.build.packageplan.PackagePlan;
import sh.zolt.build.packageplan.PackagePlanService;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.maven.repository.MavenRepositoryPathBuilder;
import sh.zolt.project.PackageMode;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.VersionPolicy;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public final class PublishDryRunService {
    private final ManifestProjectConfigLoader manifestLoader;
    private final ManifestPublishSettingsLoader publishSettingsLoader;
    private final PackagePlanService packagePlanService;
    private final ZoltLockfileReader lockfileReader;
    private final PublishPomGenerator pomGenerator;
    private final MavenRepositoryPathBuilder repositoryPathBuilder;
    private final PublishDryRunArtifactEvidencePlanner artifactEvidencePlanner;
    private final Function<String, String> environment;

    public PublishDryRunService() {
        this(
                new ManifestProjectConfigLoader(),
                new ManifestPublishSettingsLoader(),
                new PackagePlanService(),
                new PackageEvidenceManifestReader(),
                new ZoltLockfileReader(),
                new PublishPomGenerator(),
                new MavenRepositoryPathBuilder(),
                System::getenv);
    }

    public PublishDryRunService(PackagePlanService packagePlanService) {
        this(
                new ManifestProjectConfigLoader(),
                new ManifestPublishSettingsLoader(),
                packagePlanService,
                new PackageEvidenceManifestReader(),
                new ZoltLockfileReader(),
                new PublishPomGenerator(),
                new MavenRepositoryPathBuilder(),
                System::getenv);
    }

    PublishDryRunService(Function<String, String> environment) {
        this(
                new ManifestProjectConfigLoader(),
                new ManifestPublishSettingsLoader(),
                new PackagePlanService(),
                new PackageEvidenceManifestReader(),
                new ZoltLockfileReader(),
                new PublishPomGenerator(),
                new MavenRepositoryPathBuilder(),
                environment);
    }

    PublishDryRunService(
            ManifestProjectConfigLoader manifestLoader,
            ManifestPublishSettingsLoader publishSettingsLoader,
            PackagePlanService packagePlanService,
            PackageEvidenceManifestReader evidenceManifestReader,
            ZoltLockfileReader lockfileReader,
            PublishPomGenerator pomGenerator,
            MavenRepositoryPathBuilder repositoryPathBuilder,
            Function<String, String> environment) {
        this(
                manifestLoader,
                publishSettingsLoader,
                packagePlanService,
                evidenceManifestReader,
                lockfileReader,
                pomGenerator,
                repositoryPathBuilder,
                new PublishDryRunArtifactEvidencePlanner(evidenceManifestReader, repositoryPathBuilder),
                environment);
    }

    PublishDryRunService(
            ManifestProjectConfigLoader manifestLoader,
            ManifestPublishSettingsLoader publishSettingsLoader,
            PackagePlanService packagePlanService,
            PackageEvidenceManifestReader evidenceManifestReader,
            ZoltLockfileReader lockfileReader,
            PublishPomGenerator pomGenerator,
            MavenRepositoryPathBuilder repositoryPathBuilder,
            PublishDryRunArtifactEvidencePlanner artifactEvidencePlanner,
            Function<String, String> environment) {
        this.manifestLoader = manifestLoader;
        this.publishSettingsLoader = publishSettingsLoader;
        this.packagePlanService = packagePlanService;
        this.lockfileReader = lockfileReader;
        this.pomGenerator = pomGenerator;
        this.repositoryPathBuilder = repositoryPathBuilder;
        this.artifactEvidencePlanner = artifactEvidencePlanner;
        this.environment = environment;
    }

    public PublishDryRunPlan plan(Path projectRoot) {
        return plan(
                projectRoot,
                true,
                Optional.empty(),
                LocalArtifactCache.defaultRoot());
    }

    public PublishDryRunPlan plan(Path projectRoot, boolean requireRepository) {
        return plan(
                projectRoot,
                requireRepository,
                Optional.empty(),
                LocalArtifactCache.defaultRoot());
    }

    /**
     * Plans a publish. When {@code requireRepository} is false (used for Maven Central publishing,
     * where the Portal is the target rather than a Maven repository), a repository need not be
     * configured; if none is, the plan reports {@code maven-central} as the routing target.
     *
     * <p>{@code sbomFile}, when present, attaches a CycloneDX SBOM as a supplemental artifact
     * (classifier {@code cyclonedx}, extension {@code json}). It rides the existing supplemental
     * planner, so checksums and signing apply to it uniformly.
     */
    public PublishDryRunPlan plan(Path projectRoot, boolean requireRepository, Optional<Path> sbomFile) {
        return plan(
                projectRoot,
                requireRepository,
                sbomFile,
                LocalArtifactCache.defaultRoot());
    }

    public PublishDryRunPlan plan(
            Path projectRoot,
            boolean requireRepository,
            Optional<Path> sbomFile,
            Path cacheRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        ProjectConfig config = manifestLoader.loadProject(root);
        PublishSettings publish = publishSettingsLoader.read(root.resolve("zolt.toml"));
        if (!publish.configured()) {
            throw new PublishException("No [publish] configuration found. Add release/snapshot publish repositories before running `zolt publish --dry-run`.");
        }
        return planResolved(
                root,
                config,
                publish,
                () -> lockfile(root),
                () -> packagePlan(root, config, cacheRoot),
                requireRepository,
                sbomFile);
    }

    /**
     * Plans a publish from already-resolved inputs — the (policy-merged) project config, publish
     * settings, and the lockfile that drives POM generation — rather than re-reading them from disk.
     * This is the reuse seam for {@code zolt publish --workspace}: each member plans against its
     * projected member lock (directness from the member config, versions from the aggregated lock)
     * while sharing the single-project supplemental/SBOM/checksum planning, repository-credential and
     * URL-safety policy verbatim. The lockfile and package plan are supplied lazily so a repository or
     * artifact-selector rejection is raised before any lock read or package planning.
     */
    public PublishDryRunPlan planResolved(
            Path projectRoot,
            ProjectConfig config,
            PublishSettings publish,
            Supplier<ZoltLockfile> lockfileSupplier,
            Supplier<PackagePlan> packagePlanSupplier,
            boolean requireRepository,
            Optional<Path> sbomFile) {
        Path root = projectRoot.toAbsolutePath().normalize();
        String versionKind = VersionPolicy.classifyPublishVersion(config.project().version());
        String repositoryId = versionKind.equals("snapshot")
                ? publish.snapshotRepository()
                : publish.releaseRepository();
        List<String> blockers = new ArrayList<>();
        PublishRepositorySettings repository = null;
        // A Central publish (requireRepository == false) routes to the Portal, so it must NOT resolve or
        // apply the credential blockers of a configured internal release/snapshot repository: an
        // unrelated internal Nexus with unset credential env vars is irrelevant to Central and would
        // otherwise block it. Internal-repository validation belongs to the plain path only.
        if (requireRepository) {
            if (repositoryId.isBlank()) {
                throw new PublishException("Project version `"
                        + config.project().version()
                        + "` requires [publish]."
                        + (versionKind.equals("snapshot") ? "snapshotRepository" : "releaseRepository")
                        + " for `zolt publish --dry-run`.");
            }
            repository = publish.repositories().get(repositoryId);
            if (repository == null) {
                throw new PublishException("Publish repository `" + repositoryId + "` is not defined.");
            }
            if (PublishRepositoryBlockers.hasEmbeddedCredentials(repository.url())) {
                blockers.add("publish repository `"
                        + repository.id()
                        + "` URL contains embedded credentials. Move credentials to [credentials] environment references.");
            }
            blockers.addAll(PublishRepositoryBlockers.credentialBlockers(
                    repository, config.repositoryCredentials(), environment));
        }
        String displayRepositoryId = repository != null ? repository.id() : "maven-central";
        String displayRepositoryUrl = repository != null
                ? PublishRepositoryBlockers.redactedRepositoryUrl(repository.url())
                : PublishCentralSettings.DEFAULT_BASE_URL;
        Coordinate coordinate = coordinate(config);

        if (config.packageSettings().mode() == PackageMode.BOM) {
            return new PublishDryRunBomPlanner(
                            artifactEvidencePlanner,
                            repositoryPathBuilder)
                    .plan(
                    root,
                    config,
                    packagePlanSupplier.get(),
                    versionKind,
                    displayRepositoryId,
                    displayRepositoryUrl,
                    coordinate,
                    blockers);
        }

        // Selector validation must raise before any lock read or package planning (order preserved).
        String artifactId = PublishArtifactSelector.select(publish.artifacts(), config.packageSettings().mode());
        ZoltLockfile lockfile = lockfileSupplier.get();
        PackagePlan currentPlan = packagePlanSupplier.get();
        Path artifactPath = currentPlan.archivePath();
        Path evidencePath = PackageEvidenceManifestWriter.evidenceManifestPath(artifactPath);
        Path pomPath = root.resolve(config.build().outputRoot()).resolve("publish")
                .resolve(config.project().name() + "-" + config.project().version() + ".pom")
                .normalize();
        String pomSha256 = writePom(root, pomPath, config, lockfile);
        String artifactUploadPath = repositoryPathBuilder.artifactPath(
                new ArtifactDescriptor(
                        coordinate,
                        Optional.empty(),
                        PublishDryRunArtifactEvidencePlanner.extension(artifactPath)));
        String pomUploadPath = repositoryPathBuilder.pomPath(coordinate);
        PublishDryRunArtifactEvidence artifactEvidence = artifactEvidencePlanner.plan(
                root,
                coordinate,
                currentPlan,
                artifactPath,
                evidencePath,
                blockers);
        List<PublishArtifactPlan> supplementalArtifacts = new ArrayList<>(artifactEvidence.supplementalArtifacts());
        sbomFile.ifPresent(file -> supplementalArtifacts.add(
                PublishSbomArtifactPlanner.plan(root, coordinate, file, repositoryPathBuilder)));
        List<PublishChecksumSidecar> checksumSidecars = PublishChecksumSidecarPlanner.plan(
                root,
                artifactPath,
                artifactUploadPath,
                supplementalArtifacts,
                pomPath,
                pomUploadPath);

        return new PublishDryRunPlan(
                config.project().group() + ":" + config.project().name() + ":" + config.project().version(),
                versionKind,
                displayRepositoryId,
                displayRepositoryUrl,
                artifactId,
                PublishDryRunArtifactEvidencePlanner.display(root, artifactPath),
                artifactEvidence.artifactSha256(),
                artifactUploadPath,
                List.copyOf(supplementalArtifacts),
                PublishDryRunArtifactEvidencePlanner.display(root, evidencePath),
                PublishDryRunArtifactEvidencePlanner.display(root, pomPath),
                pomSha256,
                pomUploadPath,
                checksumSidecars,
                "",
                blockers,
                false);
    }

    private static Coordinate coordinate(ProjectConfig config) {
        return new Coordinate(
                config.project().group(),
                config.project().name(),
                Optional.of(config.project().version()));
    }

    private ZoltLockfile lockfile(Path root) {
        try {
            return lockfileReader.read(ProjectLockfile.in(root));
        } catch (LockfileReadException exception) {
            throw new PublishException("Could not read zolt.lock for publish metadata: " + exception.getMessage());
        }
    }

    private PackagePlan packagePlan(
            Path root,
            ProjectConfig config,
            Path cacheRoot) {
        try {
            return packagePlanService.plan(
                    root,
                    config,
                    ProjectLockfile.in(root),
                    cacheRoot);
        } catch (LockfileReadException exception) {
            throw new PublishException("Could not plan publish artifact: " + exception.getMessage());
        }
    }

    private String writePom(Path root, Path pomPath, ProjectConfig config, ZoltLockfile lockfile) {
        try {
            Files.createDirectories(pomPath.getParent());
            Files.writeString(pomPath, pomGenerator.generate(config, lockfile));
            return PublishChecksum.sha256(pomPath);
        } catch (IOException exception) {
            throw new PublishException(
                    "Could not write publish POM preview at "
                            + PublishDryRunArtifactEvidencePlanner.displayPath(root, pomPath)
                            + ".",
                    exception);
        }
    }
}
