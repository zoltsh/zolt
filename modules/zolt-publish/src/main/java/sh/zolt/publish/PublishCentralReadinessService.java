package sh.zolt.publish;

import sh.zolt.project.ProjectConfig;
import sh.zolt.project.PublicationMetadata;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Gathers the inputs a {@link PublishCentralReadiness} evaluation needs — publication metadata and
 * version from {@code zolt.toml}, sources/Javadoc presence from an already-computed dry-run plan —
 * and produces the Maven Central requirement report.
 */
public final class PublishCentralReadinessService {
    private final ManifestProjectConfigLoader manifestLoader;
    private final PublishSettingsReader publishSettingsReader;
    private final java.util.function.UnaryOperator<String> environment;

    public PublishCentralReadinessService() {
        this(new ManifestProjectConfigLoader(), new PublishSettingsReader());
    }

    PublishCentralReadinessService(ManifestProjectConfigLoader manifestLoader, PublishSettingsReader publishSettingsReader) {
        this(manifestLoader, publishSettingsReader, System::getenv);
    }

    PublishCentralReadinessService(
            ManifestProjectConfigLoader manifestLoader,
            PublishSettingsReader publishSettingsReader,
            java.util.function.UnaryOperator<String> environment) {
        this.manifestLoader = manifestLoader;
        this.publishSettingsReader = publishSettingsReader;
        this.environment = environment;
    }

    public List<PublishCentralRequirement> evaluate(Path projectRoot, PublishDryRunPlan plan) {
        Path root = projectRoot.toAbsolutePath().normalize();
        ProjectConfig config = manifestLoader.load(root.resolve("zolt.toml"));
        PublishSettings publish = publishSettingsReader.read(root.resolve("zolt.toml"), config.repositoryCredentials());
        return evaluate(config, publish, plan);
    }

    /**
     * Evaluates Central readiness from already-resolved config and publish settings. The workspace
     * publish path supplies the policy-merged member config so inherited {@code [package.metadata]}
     * and {@code [publish.signing]} are honoured without re-reading the member {@code zolt.toml}.
     */
    public List<PublishCentralRequirement> evaluate(
            ProjectConfig config, PublishSettings publish, PublishDryRunPlan plan) {
        PublicationMetadata metadata = config.packageSettings().metadata();
        // Reproducible signing (SOURCE_DATE_EPOCH) requires a pinned key; surfacing the PublishSigner
        // rejection in the checklist catches it before any signing work. The shared SourceDateEpoch
        // parser also rejects a blank/malformed/negative value loudly here, exactly as the signer does,
        // so both agree on when a build is reproducible.
        boolean reproducibleKeyMissing = publish.signing().enabled()
                && SourceDateEpoch.parse(environment).reproducible()
                && publish.signing().keyId().isEmpty();
        return PublishCentralReadiness.evaluate(
                plan.versionKind(),
                metadata,
                hasClassifier(plan, "sources"),
                hasClassifier(plan, "javadoc"),
                publish.signing().enabled(),
                config.packageSettings().mode() == sh.zolt.project.PackageMode.BOM,
                reproducibleKeyMissing);
    }

    private static boolean hasClassifier(PublishDryRunPlan plan, String classifier) {
        return plan.supplementalArtifacts().stream()
                .anyMatch(artifact -> artifact.classifier().map(classifier::equals).orElse(false));
    }
}
