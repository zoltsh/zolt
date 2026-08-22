package sh.zolt.toml.manifest.write;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestModelValues;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.authored.AuthoredCentralPublishing;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.manifest.authored.AuthoredPublicationRoutes;
import sh.zolt.manifest.authored.AuthoredPublicationSigning;
import sh.zolt.manifest.authored.AuthoredPublishing;
import sh.zolt.toml.schema.FinalManifestPaths;
import sh.zolt.toml.schema.FinalManifestPublishingFields;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestPath;
import sh.zolt.toml.schema.ManifestSection;

/** Emits canonical authored publication routes, repositories, signing, and Central settings. */
final class ManifestPublishingWriter {
    private static final RepositoryUrl DEFAULT_CENTRAL_URL =
            new RepositoryUrl("https://central.sonatype.com");
    private static final ManifestSection PUBLISH = section(FinalManifestPaths.PUBLISH);
    private static final ManifestSection REPOSITORY =
            section(FinalManifestPaths.PUBLISH_REPOSITORY);
    private static final ManifestSection SIGNING =
            section(FinalManifestPaths.PUBLISH_SIGNING);
    private static final ManifestSection CENTRAL =
            section(FinalManifestPaths.PUBLISH_CENTRAL);

    void write(ManifestTomlEmitter emitter, AuthoredPublishing publishing) {
        Objects.requireNonNull(emitter, "Manifest TOML emitter is required.");
        AuthoredPublishing authored = Objects.requireNonNull(
                publishing, "Authored publishing is required.");
        authored.routes().ifPresent(value -> writeRoutes(emitter, value));
        writeRepositories(emitter, authored.repositories());
        authored.signing().ifPresent(value -> writeSigning(emitter, value));
        authored.central().ifPresent(value -> writeCentral(emitter, value));
    }

    private static void writeRoutes(
            ManifestTomlEmitter emitter, AuthoredPublicationRoutes routes) {
        emitter.section(PUBLISH);
        routes.release().ifPresent(value -> emitter.field(
                FinalManifestPublishingFields.PUBLISH_RELEASE,
                string(value.value())));
        routes.snapshot().ifPresent(value -> emitter.field(
                FinalManifestPublishingFields.PUBLISH_SNAPSHOT,
                string(value.value())));
    }

    private static void writeRepositories(
            ManifestTomlEmitter emitter,
            Map<LocalId, AuthoredPublicationRepository> repositories) {
        repositories.entrySet().stream()
                .sorted(Comparator.comparing(
                        entry -> entry.getKey().value(),
                        ManifestModelValues.CODE_POINT_ORDER))
                .forEach(entry -> writeRepository(
                        emitter, entry.getKey(), entry.getValue()));
    }

    private static void writeRepository(
            ManifestTomlEmitter emitter,
            LocalId id,
            AuthoredPublicationRepository repository) {
        emitter.namedSection(REPOSITORY, id.value());
        emitter.field(
                FinalManifestPublishingFields.PUBLISH_REPOSITORY_URL,
                string(repository.url().value()));
        repository.credentials().ifPresent(value -> emitter.field(
                FinalManifestPublishingFields.PUBLISH_REPOSITORY_CREDENTIALS,
                string(value.value())));
    }

    private static void writeSigning(
            ManifestTomlEmitter emitter, AuthoredPublicationSigning signing) {
        emitter.section(SIGNING);
        emitter.field(
                FinalManifestPublishingFields.PUBLISH_SIGNING_METHOD,
                string(signing.method().configValue()));
        signing.keyId().ifPresent(value -> emitter.field(
                FinalManifestPublishingFields.PUBLISH_SIGNING_KEY_ID,
                string(value)));
        signing.passphraseEnvironment().ifPresent(value -> emitter.field(
                FinalManifestPublishingFields.PUBLISH_SIGNING_PASSPHRASE_ENV,
                string(value.value())));
    }

    private static void writeCentral(
            ManifestTomlEmitter emitter, AuthoredCentralPublishing central) {
        emitter.section(CENTRAL);
        emitter.field(
                FinalManifestPublishingFields.PUBLISH_CENTRAL_TOKEN_ENV,
                string(central.tokenEnvironment().value()));
        emitter.field(
                FinalManifestPublishingFields.PUBLISH_CENTRAL_MODE,
                string(central.mode().configValue()));
        central.name().ifPresent(value -> emitter.field(
                FinalManifestPublishingFields.PUBLISH_CENTRAL_NAME,
                string(value)));
        central.url()
                .filter(value -> !value.normalizedIdentity().equals(
                        DEFAULT_CENTRAL_URL.normalizedIdentity()))
                .ifPresent(value -> emitter.field(
                        FinalManifestPublishingFields.PUBLISH_CENTRAL_URL,
                        string(value.value())));
    }

    private static String string(String value) {
        return ManifestTomlValueEncoder.basicString(value);
    }

    private static ManifestSection section(ManifestPath path) {
        return FinalManifestSchema.registry().section(path).orElseThrow();
    }
}
