package sh.zolt.publish;

import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Orchestrates Sonatype Central Portal publishing: assembles the upload bundle (dry run) and, for a
 * live publish, uploads it and reports the deployment id and status. Reads {@code [publish.central]}
 * and {@code [publish.signing]} from {@code zolt.toml}; all network access is delegated to
 * {@link CentralPortalClient}.
 */
public final class PublishCentralPublishService {
    private final ManifestProjectConfigLoader manifestLoader;
    private final ManifestPublishSettingsLoader publishSettingsLoader;
    private final CentralPortalClient portalClient;
    private final CentralDeploymentWaiter waiter;
    private final Function<String, String> environment;

    public PublishCentralPublishService() {
        this(new ManifestProjectConfigLoader(), new ManifestPublishSettingsLoader(), new CentralPortalClient(), System::getenv);
    }

    public PublishCentralPublishService(CentralPortalClient portalClient) {
        this(new ManifestProjectConfigLoader(), new ManifestPublishSettingsLoader(), portalClient, System::getenv);
    }

    PublishCentralPublishService(
            ManifestProjectConfigLoader manifestLoader,
            ManifestPublishSettingsLoader publishSettingsLoader,
            CentralPortalClient portalClient,
            Function<String, String> environment) {
        this.manifestLoader = manifestLoader;
        this.publishSettingsLoader = publishSettingsLoader;
        this.portalClient = portalClient;
        this.waiter = new CentralDeploymentWaiter(portalClient);
        this.environment = environment;
    }

    /**
     * Assembles the Central bundle locally for a dry run, without any network access and without
     * invoking gpg: it lays out the artifacts, POM, and checksums so the structure can be inspected.
     * The real {@link #publish} signs every file.
     */
    public PublishCentralBundleResult assembleBundle(Path projectRoot, PublishDryRunPlan plan) {
        Path root = projectRoot.toAbsolutePath().normalize();
        return new PublishCentralBundle(PublishSigningSettings.disabled(), environment).assemble(root, plan);
    }

    /** Assembles the bundle, uploads it to the Central Portal, and returns the deployment status. */
    public PublishCentralUploadResult publish(Path projectRoot, PublishDryRunPlan plan) {
        return publish(projectRoot, plan, Optional.empty());
    }

    /**
     * Assembles the bundle, uploads it, and reports the outcome. When {@code waitTimeout} is present
     * the deployment is polled until it reaches a terminal state (published, failed, or — for
     * user-managed deployments — validated) or the timeout elapses. When it is empty the upload
     * returns immediately with the deployment id, an empty status, and
     * {@link PublishCentralPublishOutcome#UPLOADED}, making no status request — a transient status
     * failure must never turn a live deployment into a command failure and provoke a duplicate retry.
     */
    public PublishCentralUploadResult publish(
            Path projectRoot, PublishDryRunPlan plan, Optional<Duration> waitTimeout) {
        Path root = projectRoot.toAbsolutePath().normalize();
        PublishSettings publish = read(root);
        PublishCentralSettings central = publish.central();
        if (!central.configured()) {
            throw new PublishException(
                    "No [publish.central] configuration found. Next: add [publish.central] with a tokenEnv "
                            + "to publish to Maven Central.");
        }
        String tokenEnv = central.tokenEnv().orElseThrow(() -> new PublishException(
                "[publish.central].tokenEnv is required to publish to Maven Central."));
        String token = environment.apply(tokenEnv);
        if (token == null || token.isBlank()) {
            throw new PublishException(
                    "Central Portal token environment variable " + tokenEnv + " is not set. Next: export "
                            + tokenEnv + " with your base64 Portal user token.");
        }
        PublishCentralBundleResult bundle = new PublishCentralBundle(publish.signing(), environment)
                .assemble(root, plan);
        String deploymentId = portalClient.upload(
                central.baseUrl(), bundle.bundlePath(), token, central.publishingType(), central.deploymentName());
        if (waitTimeout.isEmpty()) {
            // No --wait: return as soon as the bundle is accepted, making no status request. Mirrors the
            // workspace family path; a transient status 500/timeout must not fail an accepted deployment.
            return new PublishCentralUploadResult(
                    bundle, deploymentId, central.publishingType(), Optional.empty(),
                    PublishCentralPublishOutcome.UPLOADED);
        }
        CentralDeploymentStatus status = waiter.awaitTerminal(
                central.baseUrl(), deploymentId, token, central.publishingType(), waitTimeout.get());
        return new PublishCentralUploadResult(
                bundle, deploymentId, central.publishingType(), Optional.of(status),
                PublishCentralPublishOutcome.ofTerminalState(status.state()));
    }

    private PublishSettings read(Path root) {
        ProjectConfig config = manifestLoader.load(root.resolve("zolt.toml"));
        return publishSettingsLoader.read(root.resolve("zolt.toml"));
    }
}
