package sh.zolt.manifest.effective;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import sh.zolt.manifest.CentralRepositoryControl;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestSource;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredDependencyRepositories;
import sh.zolt.manifest.authored.AuthoredManifest;

/** Composes the closed set of shared configuration for a standalone project. */
final class EffectiveStandaloneSharedComposer {
    private static final LocalId CENTRAL = new LocalId("central");

    private final EffectiveToolchainsComposer toolchains = new EffectiveToolchainsComposer();

    EffectiveSharedConfiguration compose(
            AuthoredManifest authored,
            EffectiveProjectIdentity identity,
            String manifestPath,
            boolean bom) {
        requireNoBomCoverage(authored.build().coverage(), bom);
        return new EffectiveSharedConfiguration(
                authored.versions()
                        .map(values -> effectiveValues(
                                values.entries(), manifestPath, "versions", LocalId::value))
                        .orElseGet(Map::of),
                repositories(authored.repositories(), manifestPath),
                authored.credentials()
                        .map(values -> effectiveValues(
                                values.entries(), manifestPath, "credentials", LocalId::value))
                        .orElseGet(Map::of),
                authored.platforms()
                        .map(values -> effectiveValues(
                                values.entries(),
                                manifestPath,
                                "platforms",
                                DependencyCoordinate::value))
                        .orElseGet(Map::of),
                toolchains.compose(authored.toolchains(), identity, manifestPath, bom),
                coverage(authored.build().coverage(), manifestPath),
                commands(authored.commands(), manifestPath));
    }

    static EffectiveDependencyRepositories repositories(
            Optional<AuthoredDependencyRepositories> authored,
            String manifestPath) {
        if (authored.isEmpty()) {
            return defaultRepositories();
        }
        AuthoredDependencyRepositories repositories = authored.orElseThrow();
        Map<LocalId, EffectiveValue<DependencyRepository>> named = effectiveValues(
                repositories.named(), manifestPath, "repositories", LocalId::value);
        EffectiveValue<EffectiveCentralRepository> central = repositories.control()
                .flatMap(control -> control.central())
                .map(value -> EffectiveValue.authored(
                        central(value, repositories),
                        source(manifestPath, "repositories", "central")))
                .orElseGet(EffectiveStandaloneSharedComposer::builtInCentral);
        EffectiveValue<List<LocalId>> order = repositories.control()
                .flatMap(control -> control.order())
                .map(value -> EffectiveValue.authored(
                        value, source(manifestPath, "repositories", "order")))
                .orElseGet(() -> EffectiveValue.builtIn(repositories.lookupOrder()));
        return new EffectiveDependencyRepositories(central, named, order);
    }

    private static EffectiveCentralRepository central(
            CentralRepositoryControl control,
            AuthoredDependencyRepositories repositories) {
        return control instanceof CentralRepositoryControl.Disabled
                ? EffectiveCentralRepository.disabled()
                : EffectiveCentralRepository.enabled(repositories.centralRepository().orElseThrow());
    }

    private static EffectiveDependencyRepositories defaultRepositories() {
        return new EffectiveDependencyRepositories(
                builtInCentral(), Map.of(), EffectiveValue.builtIn(List.of(CENTRAL)));
    }

    private static EffectiveValue<EffectiveCentralRepository> builtInCentral() {
        return EffectiveValue.builtIn(EffectiveCentralRepository.enabled(
                DependencyRepository.unauthenticated(
                        AuthoredDependencyRepositories.MAVEN_CENTRAL_URL)));
    }

    /**
     * Design §12.6: a BOM may not author tests and has no compilable sources, so it has no execution
     * domain for §10.10 coverage floors to gate. The authored layer defers the decision (a shared
     * domain is only meaningful once the BOM-ness of the manifest is known), so composition is where
     * an authored floor on a BOM is rejected.
     */
    static void requireNoBomCoverage(Optional<AuthoredCoverage> authored, boolean bom) {
        if (bom && authored.isPresent()) {
            throw new IllegalArgumentException(
                    "An effective BOM cannot author coverage floors; a BOM has no compilable sources"
                            + " or tests to measure. Remove [coverage] from the BOM manifest and author"
                            + " it on the workspace root or on the members that run tests.");
        }
    }

    static EffectiveCoverage coverage(
            Optional<AuthoredCoverage> authored,
            String manifestPath) {
        if (authored.isEmpty()) {
            return EffectiveCoverage.empty();
        }
        AuthoredCoverage coverage = authored.orElseThrow();
        return new EffectiveCoverage(
                coverage.line().map(value -> EffectiveValue.authored(
                        value, source(manifestPath, "coverage", "line"))),
                coverage.branch().map(value -> EffectiveValue.authored(
                        value, source(manifestPath, "coverage", "branch"))),
                coverage.instruction().map(value -> EffectiveValue.authored(
                        value, source(manifestPath, "coverage", "instruction"))),
                coverage.method().map(value -> EffectiveValue.authored(
                        value, source(manifestPath, "coverage", "method"))));
    }

    static EffectiveCommands commands(
            Optional<AuthoredCommands> authored,
            String manifestPath) {
        if (authored.isEmpty()) {
            return EffectiveCommands.empty();
        }
        AuthoredCommands commands = authored.orElseThrow();
        return new EffectiveCommands(
                effectiveValues(commands.tasks(), manifestPath, "tasks", LocalId::value),
                effectiveValues(commands.aliases(), manifestPath, "aliases", LocalId::value));
    }

    static <K, V> Map<K, EffectiveValue<V>> effectiveValues(
            Map<K, V> values,
            String manifestPath,
            String table,
            Function<K, String> keyName) {
        return values.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                entry -> EffectiveValue.authored(
                        entry.getValue(),
                        source(manifestPath, table, keyName.apply(entry.getKey())))));
    }

    static ManifestSource source(String manifestPath, String... path) {
        return new ManifestSource(manifestPath, List.of(path));
    }
}
