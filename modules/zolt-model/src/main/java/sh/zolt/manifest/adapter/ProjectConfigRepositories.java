package sh.zolt.manifest.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.effective.EffectiveCentralRepository;
import sh.zolt.manifest.effective.EffectiveDependencyRepositories;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;

/**
 * Projects the effective dependency-repository universe onto the legacy {@code repositories} and
 * {@code repositoryCredentials} maps.
 *
 * <p>The legacy engine treated {@code [repositories]} document order as lookup order, so the adapter
 * emits entries in the effective {@link EffectiveDependencyRepositories#lookupOrder()} instead of the
 * sorted named-repository map order.
 *
 * <p>Both projections are iteration-order carriers, never {@code Map.copyOf} values: design §8.5 makes
 * the lookup order authored policy, and {@code Map.copyOf} publishes an unspecified, salt-randomized
 * iteration order that would silently discard it.
 */
public final class ProjectConfigRepositories {
    static final LocalId CENTRAL = new LocalId("central");

    private ProjectConfigRepositories() {
    }

    /** Legacy repository settings, keyed by ID, in effective lookup order. */
    public static Map<String, RepositorySettings> settings(EffectiveDependencyRepositories repositories) {
        Map<String, RepositorySettings> settings = new LinkedHashMap<>();
        for (LocalId id : repositories.lookupOrder().value()) {
            settings.put(id.value(), CENTRAL.equals(id)
                    ? central(id, repositories.central().value())
                    : named(id, repositories.named().get(id)));
        }
        return Collections.unmodifiableMap(settings);
    }

    /** Legacy repository credentials, keyed by ID. */
    public static Map<String, RepositoryCredentialSettings> credentials(
            Map<LocalId, EffectiveValue<RepositoryCredential>> credentials) {
        Map<String, RepositoryCredentialSettings> settings = new LinkedHashMap<>();
        credentials.forEach((id, credential) ->
                settings.put(id.value(), credential(id, credential.value())));
        return Collections.unmodifiableMap(settings);
    }

    private static RepositorySettings central(LocalId id, EffectiveCentralRepository central) {
        DependencyRepository repository = central.repository().orElseThrow(() ->
                new IllegalArgumentException(
                        "Disabled Central cannot appear in the effective repository lookup order."));
        return settings(id, repository);
    }

    private static RepositorySettings named(LocalId id, EffectiveValue<DependencyRepository> repository) {
        if (repository == null) {
            throw new IllegalArgumentException(
                    "Effective repository lookup order names undefined repository `" + id + "`.");
        }
        return settings(id, repository.value());
    }

    private static RepositorySettings settings(LocalId id, DependencyRepository repository) {
        return new RepositorySettings(
                id.value(),
                repository.url().value(),
                repository.credentials().map(LocalId::value));
    }

    private static RepositoryCredentialSettings credential(LocalId id, RepositoryCredential credential) {
        return switch (credential) {
            case RepositoryCredential.BearerToken token -> new RepositoryCredentialSettings(
                    id.value(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(token.tokenEnvironment().value()));
            case RepositoryCredential.Basic basic -> new RepositoryCredentialSettings(
                    id.value(),
                    Optional.of(basic.usernameEnvironment().value()),
                    Optional.of(basic.passwordEnvironment().value()),
                    Optional.empty());
        };
    }
}
