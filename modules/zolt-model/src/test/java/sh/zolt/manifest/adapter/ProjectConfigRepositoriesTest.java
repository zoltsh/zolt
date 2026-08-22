package sh.zolt.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.RepositoryUrl;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.effective.EffectiveCentralRepository;
import sh.zolt.manifest.effective.EffectiveDependencyRepositories;
import sh.zolt.manifest.effective.EffectiveValue;
import sh.zolt.project.RepositorySettings;

/**
 * The legacy repository projection is an order carrier: design §8.5 makes lookup order authored
 * policy, and the fetch loop is first-match-wins.
 */
final class ProjectConfigRepositoriesTest {
    private static final LocalId CENTRAL = new LocalId("central");

    /**
     * Two authored orders over one repository set. A hash-ordered projection ({@code Map.copyOf})
     * yields one iteration order for one key set, so it cannot satisfy both expectations — the pair
     * fails deterministically if the adapter ever stops carrying order.
     */
    @Test
    void projectsRepositoriesInTheAuthoredLookupOrder() {
        List<LocalId> snapshotsFirst =
                List.of(new LocalId("snapshots"), new LocalId("releases"), CENTRAL);
        List<LocalId> releasesFirst =
                List.of(new LocalId("releases"), CENTRAL, new LocalId("snapshots"));

        assertEquals(
                List.of("snapshots", "releases", "central"),
                List.copyOf(ProjectConfigRepositories.settings(authoredOrder(snapshotsFirst)).keySet()));
        assertEquals(
                List.of("releases", "central", "snapshots"),
                List.copyOf(ProjectConfigRepositories.settings(authoredOrder(releasesFirst)).keySet()));
    }

    @Test
    void placesCentralLastByDefaultBehindEveryCustomRepository() {
        Map<String, RepositorySettings> settings = ProjectConfigRepositories.settings(
                defaultOrder(List.of(new LocalId("releases"), new LocalId("snapshots"), CENTRAL)));

        assertEquals(List.of("releases", "snapshots", "central"), List.copyOf(settings.keySet()));
        assertEquals(
                AuthoredDependencyRepositories.MAVEN_CENTRAL_URL.value(),
                settings.get("central").url());
        assertEquals("https://repo.example.test/releases", settings.get("releases").url());
    }

    @Test
    void projectsAnEmptyUniverseWhenCentralIsDisabledWithoutCustomRepositories() {
        EffectiveDependencyRepositories repositories = new EffectiveDependencyRepositories(
                EffectiveValue.authored(
                        EffectiveCentralRepository.disabled(),
                        new ManifestSource("zolt.toml", List.of("repositories", "central"))),
                Map.of(),
                EffectiveValue.builtIn(List.of()));

        assertEquals(Map.of(), ProjectConfigRepositories.settings(repositories));
    }

    private static EffectiveDependencyRepositories authoredOrder(List<LocalId> order) {
        return repositories(order, EffectiveValue.authored(
                order, new ManifestSource("zolt.toml", List.of("repositories", "order"))));
    }

    private static EffectiveDependencyRepositories defaultOrder(List<LocalId> order) {
        return repositories(order, EffectiveValue.builtIn(order));
    }

    private static EffectiveDependencyRepositories repositories(
            List<LocalId> order,
            EffectiveValue<List<LocalId>> lookupOrder) {
        Map<LocalId, EffectiveValue<DependencyRepository>> named = new LinkedHashMap<>();
        for (LocalId id : order) {
            if (CENTRAL.equals(id)) {
                continue;
            }
            named.put(id, EffectiveValue.authored(
                    DependencyRepository.unauthenticated(
                            new RepositoryUrl("https://repo.example.test/" + id.value())),
                    new ManifestSource("zolt.toml", List.of("repositories", id.value()))));
        }
        return new EffectiveDependencyRepositories(
                EffectiveValue.builtIn(EffectiveCentralRepository.enabled(
                        DependencyRepository.unauthenticated(
                                AuthoredDependencyRepositories.MAVEN_CENTRAL_URL))),
                named,
                lookupOrder);
    }
}
