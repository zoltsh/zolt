package sh.zolt.manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Immutable authored dependency-repository universe with exact lookup-order validation. */
public record AuthoredDependencyRepositories(
        Optional<AuthoredRepositoryControl> control,
        Map<LocalId, DependencyRepository> named) {
    public static final RepositoryUrl MAVEN_CENTRAL_URL =
            new RepositoryUrl("https://repo.maven.apache.org/maven2");

    private static final LocalId CENTRAL = new LocalId("central");
    private static final LocalId ORDER = new LocalId("order");

    public AuthoredDependencyRepositories {
        control = Objects.requireNonNull(control, "Authored repository control must not be null.");
        named = immutableNamedRepositories(named);
        validateOrder(control, named.keySet());
    }

    public static AuthoredDependencyRepositories defaults() {
        return new AuthoredDependencyRepositories(Optional.empty(), Map.of());
    }

    public boolean centralEnabled() {
        return control.flatMap(AuthoredRepositoryControl::central)
                .map(value -> !(value instanceof CentralRepositoryControl.Disabled))
                .orElse(true);
    }

    public Optional<DependencyRepository> centralRepository() {
        Optional<CentralRepositoryControl> authored = control.flatMap(AuthoredRepositoryControl::central);
        if (authored.filter(CentralRepositoryControl.Disabled.class::isInstance).isPresent()) {
            return Optional.empty();
        }
        if (authored.filter(CentralRepositoryControl.Replacement.class::isInstance).isPresent()) {
            CentralRepositoryControl.Replacement replacement =
                    (CentralRepositoryControl.Replacement) authored.orElseThrow();
            return Optional.of(new DependencyRepository(replacement.url(), replacement.credentials()));
        }
        return Optional.of(DependencyRepository.unauthenticated(MAVEN_CENTRAL_URL));
    }

    /** Exact explicit order, or the final default of sorted custom IDs followed by Central. */
    public List<LocalId> lookupOrder() {
        Optional<List<LocalId>> authored = control.flatMap(AuthoredRepositoryControl::order);
        if (authored.isPresent()) {
            return authored.orElseThrow();
        }
        ArrayList<LocalId> defaults = new ArrayList<>(named.keySet());
        if (centralEnabled()) {
            defaults.add(CENTRAL);
        }
        return List.copyOf(defaults);
    }

    public Set<LocalId> credentialReferences() {
        HashSet<LocalId> references = new HashSet<>();
        named.values().forEach(repository -> repository.credentials().ifPresent(references::add));
        control.flatMap(AuthoredRepositoryControl::central)
                .filter(CentralRepositoryControl.Replacement.class::isInstance)
                .map(CentralRepositoryControl.Replacement.class::cast)
                .flatMap(CentralRepositoryControl.Replacement::credentials)
                .ifPresent(references::add);
        return Set.copyOf(references);
    }

    private static Map<LocalId, DependencyRepository> immutableNamedRepositories(
            Map<LocalId, DependencyRepository> values) {
        Objects.requireNonNull(values, "Authored named repositories must not be null.");
        TreeMap<LocalId, DependencyRepository> copy = new TreeMap<>();
        values.forEach((id, repository) -> {
            Objects.requireNonNull(id, "Named repository ID must not be null.");
            Objects.requireNonNull(repository, "Named repository must not be null.");
            if (id.equals(CENTRAL) || id.equals(ORDER)) {
                throw new IllegalArgumentException("`" + id + "` is reserved and cannot be a named repository ID.");
            }
            copy.put(id, repository);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static void validateOrder(
            Optional<AuthoredRepositoryControl> control,
            Set<LocalId> namedIds) {
        if (control.isEmpty() || control.orElseThrow().order().isEmpty()) {
            return;
        }
        Set<LocalId> expected = new HashSet<>(namedIds);
        Optional<CentralRepositoryControl> central = control.orElseThrow().central();
        if (central.isEmpty() || !(central.orElseThrow() instanceof CentralRepositoryControl.Disabled)) {
            expected.add(CENTRAL);
        }
        Set<LocalId> actual = Set.copyOf(control.orElseThrow().order().orElseThrow());
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    "Repository order must list every enabled repository ID exactly once; expected " + expected
                            + " but got " + actual + ".");
        }
    }
}
