package sh.zolt.publish;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.authored.AuthoredCentralPublishing;
import sh.zolt.manifest.authored.AuthoredPublicationRepository;
import sh.zolt.manifest.authored.AuthoredPublicationRoutes;
import sh.zolt.manifest.authored.AuthoredPublicationSigning;
import sh.zolt.manifest.authored.AuthoredPublishing;

/**
 * Projects the final {@code [publish]} domain onto the legacy {@link PublishSettings}.
 *
 * <p>Two legacy fields have no final source:
 *
 * <ul>
 *   <li>{@code [publish].artifacts} — design §14.1 makes package mode the sole main-artifact
 *       selector, so the adapter always reports the legacy default {@code ["main"]};
 *   <li>{@code [publish.signing].enabled} — design §14.2 makes the table itself the signal and
 *       requires an explicit {@code method}, so presence enables signing.
 * </ul>
 */
public final class ManifestPublishSettingsAdapter {
    private ManifestPublishSettingsAdapter() {
    }

    /** Adapts one authored publishing domain, treating an absent domain as unconfigured. */
    public static PublishSettings adapt(Optional<AuthoredPublishing> publishing) {
        if (publishing.isEmpty()) {
            return new PublishSettings("", "", List.of(), Map.of());
        }
        AuthoredPublishing authored = publishing.orElseThrow();
        Optional<AuthoredPublicationRoutes> routes = authored.routes();
        return new PublishSettings(
                routes.flatMap(AuthoredPublicationRoutes::release).map(LocalId::value).orElse(""),
                routes.flatMap(AuthoredPublicationRoutes::snapshot).map(LocalId::value).orElse(""),
                List.of("main"),
                repositories(authored.repositories()),
                signing(authored.signing()),
                central(authored.central()));
    }

    private static Map<String, PublishRepositorySettings> repositories(
            Map<LocalId, AuthoredPublicationRepository> repositories) {
        Map<String, PublishRepositorySettings> settings = new LinkedHashMap<>();
        repositories.forEach((id, repository) -> settings.put(
                id.value(),
                new PublishRepositorySettings(
                        id.value(),
                        repository.url().value(),
                        repository.credentials().map(LocalId::value))));
        return Map.copyOf(settings);
    }

    private static PublishSigningSettings signing(Optional<AuthoredPublicationSigning> signing) {
        return signing
                .map(authored -> new PublishSigningSettings(
                        true,
                        authored.keyId(),
                        authored.passphraseEnvironment().map(name -> name.value())))
                .orElseGet(PublishSigningSettings::disabled);
    }

    private static PublishCentralSettings central(Optional<AuthoredCentralPublishing> central) {
        return central
                .map(authored -> new PublishCentralSettings(
                        true,
                        Optional.of(authored.tokenEnvironment().value()),
                        publishingType(authored.mode()),
                        authored.name(),
                        authored.url().map(RepositoryUrl::value)
                                .orElse(PublishCentralSettings.DEFAULT_BASE_URL)))
                .orElseGet(PublishCentralSettings::none);
    }

    private static CentralPublishingType publishingType(AuthoredCentralPublishing.Mode mode) {
        return switch (mode) {
            case MANUAL -> CentralPublishingType.USER_MANAGED;
            case AUTOMATIC -> CentralPublishingType.AUTOMATIC;
        };
    }
}
