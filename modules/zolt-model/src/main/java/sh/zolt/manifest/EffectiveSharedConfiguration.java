package sh.zolt.manifest;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

/** Closed set of workspace-shareable effective configuration. */
public record EffectiveSharedConfiguration(
        Map<LocalId, EffectiveValue<VersionAliasValue>> versions,
        EffectiveDependencyRepositories repositories,
        Map<LocalId, EffectiveValue<RepositoryCredential>> credentials,
        Map<DependencyCoordinate, EffectiveValue<PlatformSelector>> platforms,
        EffectiveToolchains toolchains,
        EffectiveCoverage coverage,
        EffectiveCommands commands) {
    public EffectiveSharedConfiguration {
        versions = ManifestModelValues.immutableSortedMap(
                versions,
                Comparator.naturalOrder(),
                "Effective version alias ID",
                "Effective version alias");
        rejectBuiltInValues(versions, "Effective version alias");
        repositories = Objects.requireNonNull(
                repositories, "Effective dependency repositories must not be null.");
        credentials = ManifestModelValues.immutableSortedMap(
                credentials,
                Comparator.naturalOrder(),
                "Effective credential ID",
                "Effective credential");
        rejectBuiltInValues(credentials, "Effective credential");
        platforms = ManifestModelValues.immutableSortedMap(
                platforms,
                Comparator.naturalOrder(),
                "Effective platform coordinate",
                "Effective platform");
        rejectBuiltInValues(platforms, "Effective platform");
        toolchains = Objects.requireNonNull(toolchains, "Effective toolchains must not be null.");
        coverage = Objects.requireNonNull(coverage, "Effective coverage must not be null.");
        commands = Objects.requireNonNull(commands, "Effective commands must not be null.");
    }

    private static void rejectBuiltInValues(
            Map<?, ? extends EffectiveValue<?>> values, String label) {
        for (Map.Entry<?, ? extends EffectiveValue<?>> entry : values.entrySet()) {
            if (entry.getValue().origin() == ValueOrigin.BUILT_IN) {
                throw new IllegalArgumentException(
                        label + " `" + entry.getKey() + "` must be authored or inherited.");
            }
        }
    }
}
