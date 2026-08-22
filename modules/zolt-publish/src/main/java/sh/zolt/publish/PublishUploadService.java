package sh.zolt.publish;

import sh.zolt.maven.repository.MavenRepositoryClient;
import sh.zolt.maven.repository.RemoteFileComparison;
import sh.zolt.maven.repository.RepositoryAuthentication;
import sh.zolt.maven.repository.RepositoryClientException;
import sh.zolt.cache.LocalArtifactCache;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositoryUrlPolicy;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public final class PublishUploadService {
    private final PublishDryRunService dryRunService;
    private final ManifestProjectConfigLoader manifestLoader;
    private final ManifestPublishSettingsLoader publishSettingsLoader;
    private final MavenRepositoryClient repositoryClient;
    private final Function<String, String> environment;
    private final Function<Path, Optional<String>> transactionCleanup;

    public PublishUploadService() {
        this(new MavenRepositoryClient());
    }

    public PublishUploadService(MavenRepositoryClient repositoryClient) {
        this(
                new PublishDryRunService(),
                new ManifestProjectConfigLoader(),
                new ManifestPublishSettingsLoader(),
                repositoryClient,
                System::getenv);
    }

    PublishUploadService(
            PublishDryRunService dryRunService,
            ManifestProjectConfigLoader manifestLoader,
            ManifestPublishSettingsLoader publishSettingsLoader,
            MavenRepositoryClient repositoryClient,
            Function<String, String> environment) {
        this(
                dryRunService,
                manifestLoader,
                publishSettingsLoader,
                repositoryClient,
                environment,
                PublicationTransactionManifest::deleteTransaction);
    }

    PublishUploadService(
            PublishDryRunService dryRunService,
            ManifestProjectConfigLoader manifestLoader,
            ManifestPublishSettingsLoader publishSettingsLoader,
            MavenRepositoryClient repositoryClient,
            Function<String, String> environment,
            Function<Path, Optional<String>> transactionCleanup) {
        this.dryRunService = dryRunService;
        this.manifestLoader = manifestLoader;
        this.publishSettingsLoader = publishSettingsLoader;
        this.repositoryClient = repositoryClient;
        this.environment = environment;
        this.transactionCleanup = transactionCleanup;
    }

    public PublishUploadResult upload(Path projectRoot) {
        return upload(
                projectRoot,
                Optional.empty(),
                LocalArtifactCache.defaultRoot());
    }

    public PublishUploadResult upload(Path projectRoot, Optional<Path> sbomFile) {
        return upload(
                projectRoot,
                sbomFile,
                LocalArtifactCache.defaultRoot());
    }

    public PublishUploadResult upload(
            Path projectRoot,
            Optional<Path> sbomFile,
            Path cacheRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        PublishDryRunPlan plan = dryRunService.plan(
                root,
                true,
                sbomFile,
                cacheRoot);
        if (!plan.ok()) {
            throw new PublishException("Publish is blocked. Run `zolt publish --dry-run` and resolve the reported blockers before uploading.");
        }
        ProjectConfig config = manifestLoader.loadProject(root);
        PublishSettings settings = publishSettingsLoader.read(root.resolve("zolt.toml"));
        PublishRepositorySettings repository = selectedRepository(settings, plan);
        Optional<RepositoryAuthentication> authentication = authentication(repository, config);
        URI repositoryUri = repositoryUri(repository);
        PublicationStagingService staging = new PublicationStagingService(environment);
        staging.preflight(settings.signing());
        List<PublicationSource> sources = publicationSources(root, plan);
        Path stagingRoot = root.resolve(plan.pomPath()).normalize().getParent().resolve("publish-staging");
        String targetIdentity = repositoryUri.normalize().toString();
        Path transactionPath =
                PublicationTransactionManifest.transactionPath(stagingRoot, targetIdentity, plan.coordinate());
        Path transactionFiles = PublicationTransactionManifest.transactionFilesPath(
                stagingRoot, targetIdentity, plan.coordinate());
        String signingIdentity = staging.signingIdentity(settings.signing());
        Optional<PublicationTransactionManifest> interrupted =
                PublicationTransactionManifest.read(transactionPath);
        interrupted.ifPresent(manifest -> manifest.requireIdentity(targetIdentity, signingIdentity));
        List<StagedPublicationFile> staged = staging.stage(
                transactionFiles,
                sources,
                settings.signing(),
                interrupted.map(PublicationTransactionManifest::resume).orElse(PublicationResume.none()));
        if (interrupted.isPresent()) {
            interrupted.orElseThrow().requirePlan(staged);
        } else {
            PublicationTransactionManifest.of(targetIdentity, signingIdentity, staged).write(transactionPath);
        }
        try {
            for (StagedPublicationFile file : staged) {
                uploadIfNeeded(repositoryUri, file, authentication);
            }
        } catch (IllegalArgumentException exception) {
            throw new PublishException(
                    "Publish repository `" + repository.id() + "` has an invalid URL. Use a Maven-compatible repository URL.",
                    exception);
        } catch (RepositoryClientException exception) {
            throw new PublishException(exception.getMessage(), exception);
        }
        Optional<String> cleanupWarning =
                transactionCleanup.apply(transactionPath);
        return new PublishUploadResult(plan, cleanupWarning);
    }

    private void uploadIfNeeded(
            URI repositoryUri,
            StagedPublicationFile file,
            Optional<RepositoryAuthentication> authentication) {
        RemoteFileComparison existing = repositoryClient.compareFile(
                repositoryUri,
                file.repositoryPath(),
                authentication,
                size(file),
                file.sha256());
        if (existing == RemoteFileComparison.ABSENT) {
            repositoryClient.uploadFile(
                    repositoryUri, file.repositoryPath(), file.source(), authentication);
            return;
        }
        if (existing == RemoteFileComparison.DIFFERENT) {
            throw new PublishException(
                    "Release path `"
                            + file.repositoryPath()
                            + "` already holds different content. Next: bump the version or remove the stale path.");
        }
    }

    private static long size(StagedPublicationFile file) {
        try {
            return Files.size(file.source());
        } catch (IOException exception) {
            throw new PublishException(
                    "Could not inspect staged publication file `" + file.repositoryPath() + "`.",
                    exception);
        }
    }

    private static List<PublicationSource> publicationSources(Path root, PublishDryRunPlan plan) {
        List<PublicationSource> sources = new ArrayList<>();
        if (!plan.pomOnly()) {
            sources.add(new PublicationSource(
                    plan.artifactUploadPath(), root.resolve(plan.artifactPath()).normalize()));
        }
        for (PublishArtifactPlan artifact : plan.supplementalArtifacts()) {
            sources.add(new PublicationSource(
                    artifact.uploadPath(), root.resolve(artifact.path()).normalize()));
        }
        sources.add(new PublicationSource(
                plan.pomUploadPath(), root.resolve(plan.pomPath()).normalize()));
        return List.copyOf(sources);
    }

    private static URI repositoryUri(PublishRepositorySettings repository) {
        try {
            return RepositoryUrlPolicy.requireSafeUrl(
                    "Publish repository `" + repository.id() + "`",
                    repository.url(),
                    repository.credentials().isPresent());
        } catch (IllegalArgumentException exception) {
            throw new PublishException(exception.getMessage(), exception);
        }
    }

    private static PublishRepositorySettings selectedRepository(
            PublishSettings settings,
            PublishDryRunPlan plan) {
        PublishRepositorySettings repository = settings.repositories().get(plan.repositoryId());
        if (repository == null) {
            throw new PublishException("Publish repository `" + plan.repositoryId() + "` is not defined.");
        }
        return repository;
    }

    private Optional<RepositoryAuthentication> authentication(
            PublishRepositorySettings repository,
            ProjectConfig config) {
        return PublishRepositoryAuthentication.resolve(repository, config.repositoryCredentials(), environment);
    }

}
