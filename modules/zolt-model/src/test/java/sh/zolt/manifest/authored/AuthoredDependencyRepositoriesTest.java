package sh.zolt.manifest.authored;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.CentralRepositoryControl;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.RepositoryUrl;

final class AuthoredDependencyRepositoriesTest {
    private static final LocalId CENTRAL = new LocalId("central");
    private static final LocalId RELEASES = new LocalId("releases");
    private static final LocalId SNAPSHOTS = new LocalId("snapshots");

    @Test
    void omissionKeepsCentralAndCustomRepositoriesAreAdditive() {
        LinkedHashMap<LocalId, DependencyRepository> source = new LinkedHashMap<>();
        source.put(SNAPSHOTS, repository("https://repo.example.com/snapshots"));
        source.put(RELEASES, repository("https://repo.example.com/releases"));

        AuthoredDependencyRepositories repositories =
                new AuthoredDependencyRepositories(Optional.empty(), source);
        source.clear();

        assertTrue(repositories.centralEnabled());
        assertEquals(List.of(RELEASES, SNAPSHOTS), List.copyOf(repositories.named().keySet()));
        assertEquals(List.of(RELEASES, SNAPSHOTS, CENTRAL), repositories.lookupOrder());
        assertEquals("https://repo.maven.apache.org/maven2",
                repositories.centralRepository().orElseThrow().url().value());
        assertThrows(UnsupportedOperationException.class, () -> repositories.named().clear());
    }

    @Test
    void retainsExplicitCentralStatesIncludingAuthenticatedReplacement() {
        AuthoredDependencyRepositories explicitlyEnabled = repositories(
                new AuthoredRepositoryControl(
                        Optional.of(new CentralRepositoryControl.Enabled()), Optional.empty()),
                Map.of());
        AuthoredDependencyRepositories disabled = repositories(
                new AuthoredRepositoryControl(
                        Optional.of(new CentralRepositoryControl.Disabled()), Optional.empty()),
                Map.of());
        LocalId credential = new LocalId("company");
        AuthoredDependencyRepositories replaced = repositories(
                new AuthoredRepositoryControl(
                        Optional.of(new CentralRepositoryControl.Replacement(
                                new RepositoryUrl("https://repo.example.com/maven-central"),
                                Optional.of(credential))),
                        Optional.empty()),
                Map.of());

        assertTrue(explicitlyEnabled.centralEnabled());
        assertFalse(disabled.centralEnabled());
        assertTrue(disabled.centralRepository().isEmpty());
        assertTrue(replaced.centralEnabled());
        assertEquals("https://repo.example.com/maven-central",
                replaced.centralRepository().orElseThrow().url().value());
        assertEquals(Set.of(credential), replaced.credentialReferences());
    }

    @Test
    void rejectsAnExplicitlyEmptyControlTableAndReservedNamedIds() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuthoredRepositoryControl(Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthoredDependencyRepositories(
                        Optional.empty(), Map.of(CENTRAL, repository("https://x.test"))));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthoredDependencyRepositories(
                        Optional.empty(), Map.of(new LocalId("order"), repository("https://x.test"))));
    }

    @Test
    void validatesExplicitOrderAgainstEveryEnabledRepositoryExactlyOnce() {
        Map<LocalId, DependencyRepository> named = Map.of(
                RELEASES, repository("https://repo.example.com/releases"),
                SNAPSHOTS, repository("https://repo.example.com/snapshots"));

        AuthoredRepositoryControl valid = new AuthoredRepositoryControl(
                Optional.empty(), Optional.of(List.of(SNAPSHOTS, RELEASES, CENTRAL)));
        assertEquals(List.of(SNAPSHOTS, RELEASES, CENTRAL),
                repositories(valid, named).control().orElseThrow().order().orElseThrow());
        assertEquals(List.of(SNAPSHOTS, RELEASES, CENTRAL), repositories(valid, named).lookupOrder());

        assertThrows(IllegalArgumentException.class,
                () -> repositories(new AuthoredRepositoryControl(
                        Optional.empty(), Optional.of(List.of(SNAPSHOTS, RELEASES))), named));
        assertThrows(IllegalArgumentException.class,
                () -> repositories(new AuthoredRepositoryControl(
                        Optional.empty(),
                        Optional.of(List.of(SNAPSHOTS, RELEASES, CENTRAL, new LocalId("other")))), named));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthoredRepositoryControl(
                        Optional.empty(), Optional.of(List.of(SNAPSHOTS, RELEASES, RELEASES, CENTRAL))));
    }

    @Test
    void reportsOrderMismatchIdsInDeterministicCodePointOrder() {
        LocalId alpha = new LocalId("alpha");
        LocalId beta = new LocalId("beta");
        LocalId stray = new LocalId("stray");
        LocalId zeta = new LocalId("zeta");
        Map<LocalId, DependencyRepository> named = Map.of(
                zeta, repository("https://repo.example.com/zeta"),
                beta, repository("https://repo.example.com/beta"),
                alpha, repository("https://repo.example.com/alpha"));
        AuthoredRepositoryControl invalid = new AuthoredRepositoryControl(
                Optional.empty(), Optional.of(List.of(zeta, stray, alpha)));

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> repositories(invalid, named));

        assertEquals(
                "Repository order must list every enabled repository ID exactly once; expected "
                        + "[alpha, beta, central, zeta] but got [alpha, stray, zeta].",
                error.getMessage());
    }

    @Test
    void disabledCentralSupportsAnIntentionallyEmptyUniverse() {
        AuthoredRepositoryControl emptyUniverse = new AuthoredRepositoryControl(
                Optional.of(new CentralRepositoryControl.Disabled()), Optional.of(List.of()));

        AuthoredDependencyRepositories repositories = repositories(emptyUniverse, Map.of());

        assertFalse(repositories.centralEnabled());
        assertTrue(repositories.named().isEmpty());
    }

    @Test
    void disabledCentralAcceptsAnExactCustomOnlyOrderAndRejectsStrayCentral() {
        Map<LocalId, DependencyRepository> named = Map.of(
                RELEASES, repository("https://repo.example.com/releases"),
                SNAPSHOTS, repository("https://repo.example.com/snapshots"));
        AuthoredRepositoryControl valid = new AuthoredRepositoryControl(
                Optional.of(new CentralRepositoryControl.Disabled()),
                Optional.of(List.of(SNAPSHOTS, RELEASES)));

        assertEquals(List.of(SNAPSHOTS, RELEASES), repositories(valid, named).lookupOrder());
        assertThrows(IllegalArgumentException.class,
                () -> repositories(new AuthoredRepositoryControl(
                        Optional.of(new CentralRepositoryControl.Disabled()),
                        Optional.of(List.of(SNAPSHOTS, RELEASES, CENTRAL))), named));
    }

    @Test
    void repositoryOrderIsDefensivelyCopied() {
        ArrayList<LocalId> source = new ArrayList<>(List.of(SNAPSHOTS, RELEASES, CENTRAL));
        AuthoredRepositoryControl control =
                new AuthoredRepositoryControl(Optional.empty(), Optional.of(source));
        source.clear();

        assertEquals(List.of(SNAPSHOTS, RELEASES, CENTRAL), control.order().orElseThrow());
        assertThrows(UnsupportedOperationException.class,
                () -> control.order().orElseThrow().clear());
    }

    @Test
    void gathersNamedAndCentralCredentialReferencesWithoutSecrets() {
        LocalId company = new LocalId("company");
        LocalId mirror = new LocalId("mirror");
        AuthoredRepositoryControl control = new AuthoredRepositoryControl(
                Optional.of(new CentralRepositoryControl.Replacement(
                        new RepositoryUrl("https://repo.example.com/central"), Optional.of(mirror))),
                Optional.empty());
        DependencyRepository releases = new DependencyRepository(
                new RepositoryUrl("https://repo.example.com/releases"), Optional.of(company));

        assertEquals(Set.of(company, mirror),
                repositories(control, Map.of(RELEASES, releases)).credentialReferences());
    }

    private static AuthoredDependencyRepositories repositories(
            AuthoredRepositoryControl control,
            Map<LocalId, DependencyRepository> named) {
        return new AuthoredDependencyRepositories(Optional.of(control), named);
    }

    private static DependencyRepository repository(String url) {
        return DependencyRepository.unauthenticated(new RepositoryUrl(url));
    }
}
