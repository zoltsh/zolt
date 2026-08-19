package sh.zolt.manifest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Complete parser-independent authored publication domain.
 *
 * <p>Credential existence, package publishability, SNAPSHOT route selection, Central readiness,
 * and reproducible-signing environment checks require project or execution context and are
 * intentionally outside this authored aggregate.
 */
public record AuthoredPublishing(
        Optional<AuthoredPublicationRoutes> routes,
        Map<LocalId, AuthoredPublicationRepository> repositories,
        Optional<AuthoredPublicationSigning> signing,
        Optional<AuthoredCentralPublishing> central) {
    public AuthoredPublishing {
        routes = Objects.requireNonNull(routes, "Authored publication routes must not be null.");
        repositories = ManifestModelValues.immutableSortedMap(
                repositories,
                Comparator.naturalOrder(),
                "Publication repository ID",
                "Publication repository");
        signing = Objects.requireNonNull(signing, "Authored publication signing must not be null.");
        central = Objects.requireNonNull(central, "Authored Central publication settings must not be null.");
        validateRouteReferences(routes, repositories);
        validateDirectEnvironmentReferences(signing, central);
    }

    public static AuthoredPublishing empty() {
        return new AuthoredPublishing(
                Optional.empty(), Map.of(), Optional.empty(), Optional.empty());
    }

    /** Sorted credential IDs referenced by named publication repositories. */
    public List<LocalId> credentialReferences() {
        TreeSet<LocalId> references = new TreeSet<>();
        repositories.values().forEach(repository ->
                repository.credentials().ifPresent(references::add));
        return List.copyOf(references);
    }

    private static void validateRouteReferences(
            Optional<AuthoredPublicationRoutes> routes,
            Map<LocalId, AuthoredPublicationRepository> repositories) {
        if (routes.isEmpty()) {
            return;
        }
        routes.orElseThrow().release().ifPresent(repository ->
                requireRepository("release", repository, repositories));
        routes.orElseThrow().snapshot().ifPresent(repository ->
                requireRepository("snapshot", repository, repositories));
    }

    private static void requireRepository(
            String route,
            LocalId repository,
            Map<LocalId, AuthoredPublicationRepository> repositories) {
        if (!repositories.containsKey(repository)) {
            throw new IllegalArgumentException(
                    "Publication " + route + " route references undefined repository `" + repository + "`.");
        }
    }

    private static void validateDirectEnvironmentReferences(
            Optional<AuthoredPublicationSigning> signing,
            Optional<AuthoredCentralPublishing> central) {
        ArrayList<EnvironmentVariableName> references = new ArrayList<>();
        signing.flatMap(AuthoredPublicationSigning::passphraseEnvironment)
                .ifPresent(references::add);
        central.map(AuthoredCentralPublishing::tokenEnvironment)
                .ifPresent(references::add);
        ManifestModelValues.rejectEnvironmentCaseCollisions(
                references, "Publication");
    }
}
