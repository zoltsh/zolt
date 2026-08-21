package sh.zolt.manifest.effective;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencyRepository;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.PlatformSelector;
import sh.zolt.manifest.RepositoryCredential;
import sh.zolt.manifest.VersionAliasValue;
import sh.zolt.manifest.authored.AuthoredAlias;
import sh.zolt.manifest.authored.AuthoredCommands;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredCredentials;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredTask;

/** Applies the closed root/member merge policy to one non-root workspace member. */
final class EffectiveWorkspaceSharedComposer {
    private final EffectiveToolchainsComposer toolchains = new EffectiveToolchainsComposer();

    EffectiveSharedConfiguration compose(
            AuthoredManifest root,
            AuthoredManifest member,
            EffectiveProjectIdentity identity,
            String rootManifestPath,
            String memberManifestPath,
            boolean bom) {
        if (member.repositories().isPresent()) {
            throw new IllegalArgumentException(
                    "A workspace member cannot declare dependency repositories; "
                            + "the workspace root repository universe is authoritative.");
        }

        Map<LocalId, EffectiveValue<VersionAliasValue>> versions = mergeNamed(
                root.versions().map(value -> value.entries()).orElseGet(Map::of),
                member.versions().map(value -> value.entries()).orElseGet(Map::of),
                rootManifestPath, memberManifestPath, "versions", LocalId::value);
        Map<LocalId, EffectiveValue<RepositoryCredential>> credentials = mergeNamed(
                root.credentials().map(value -> value.entries()).orElseGet(Map::of),
                member.credentials().map(value -> value.entries()).orElseGet(Map::of),
                rootManifestPath, memberManifestPath, "credentials", LocalId::value);
        validateCredentialEnvironmentNames(credentials);
        Map<DependencyCoordinate, EffectiveValue<PlatformSelector>> platforms = mergeNamed(
                root.platforms().map(value -> value.entries()).orElseGet(Map::of),
                member.platforms().map(value -> value.entries()).orElseGet(Map::of),
                rootManifestPath, memberManifestPath, "platforms", DependencyCoordinate::value);

        return new EffectiveSharedConfiguration(
                versions,
                inheritRepositories(EffectiveStandaloneSharedComposer.repositories(
                        root.repositories(), rootManifestPath)),
                credentials,
                platforms,
                toolchains.composeWorkspaceMember(
                        root.toolchains(),
                        member.toolchains(),
                        identity,
                        rootManifestPath,
                        memberManifestPath,
                        bom),
                coverage(
                        root.build().coverage(),
                        member.build().coverage(),
                        rootManifestPath,
                        memberManifestPath),
                commands(
                        root.commands(),
                        member.commands(),
                        rootManifestPath,
                        memberManifestPath));
    }

    private static <K, V> Map<K, EffectiveValue<V>> mergeNamed(
            Map<K, V> root,
            Map<K, V> member,
            String rootManifestPath,
            String memberManifestPath,
            String table,
            Function<K, String> keyName) {
        LinkedHashMap<K, EffectiveValue<V>> merged = new LinkedHashMap<>();
        root.forEach((key, value) -> merged.put(
                key,
                EffectiveValue.inherited(
                        value,
                        EffectiveStandaloneSharedComposer.source(
                                rootManifestPath, table, keyName.apply(key)))));
        member.forEach((key, value) -> {
            if (merged.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Workspace root-owned " + table + " entry `" + key
                                + "` cannot be redeclared by member `"
                                + memberManifestPath + "`, even with an identical value.");
            }
            merged.put(
                    key,
                    EffectiveValue.authored(
                            value,
                            EffectiveStandaloneSharedComposer.source(
                                    memberManifestPath, table, keyName.apply(key))));
        });
        return Map.copyOf(merged);
    }

    private static EffectiveDependencyRepositories inheritRepositories(
            EffectiveDependencyRepositories repositories) {
        LinkedHashMap<LocalId, EffectiveValue<DependencyRepository>> named =
                new LinkedHashMap<>();
        repositories.named().forEach((id, value) -> named.put(id, inherited(value)));
        return new EffectiveDependencyRepositories(
                inherited(repositories.central()),
                named,
                inherited(repositories.lookupOrder()));
    }

    private static EffectiveCoverage coverage(
            Optional<AuthoredCoverage> root,
            Optional<AuthoredCoverage> member,
            String rootManifestPath,
            String memberManifestPath) {
        return new EffectiveCoverage(
                floor(root.flatMap(AuthoredCoverage::line), member.flatMap(AuthoredCoverage::line),
                        rootManifestPath, memberManifestPath, "line"),
                floor(root.flatMap(AuthoredCoverage::branch), member.flatMap(AuthoredCoverage::branch),
                        rootManifestPath, memberManifestPath, "branch"),
                floor(
                        root.flatMap(AuthoredCoverage::instruction),
                        member.flatMap(AuthoredCoverage::instruction),
                        rootManifestPath,
                        memberManifestPath,
                        "instruction"),
                floor(root.flatMap(AuthoredCoverage::method), member.flatMap(AuthoredCoverage::method),
                        rootManifestPath, memberManifestPath, "method"));
    }

    private static Optional<EffectiveValue<CoveragePercentage>> floor(
            Optional<CoveragePercentage> root,
            Optional<CoveragePercentage> member,
            String rootManifestPath,
            String memberManifestPath,
            String field) {
        if (root.isPresent() && member.isPresent()
                && member.orElseThrow().compareTo(root.orElseThrow()) < 0) {
            throw new IllegalArgumentException(
                    "Member coverage." + field + " floor " + member.orElseThrow().value()
                            + " cannot lower workspace minimum " + root.orElseThrow().value() + ".");
        }
        if (member.isPresent()) {
            return Optional.of(EffectiveValue.authored(
                    member.orElseThrow(),
                    EffectiveStandaloneSharedComposer.source(
                            memberManifestPath, "coverage", field)));
        }
        return root.map(value -> EffectiveValue.inherited(
                value,
                EffectiveStandaloneSharedComposer.source(
                        rootManifestPath, "coverage", field)));
    }

    private static EffectiveCommands commands(
            Optional<AuthoredCommands> root,
            Optional<AuthoredCommands> member,
            String rootManifestPath,
            String memberManifestPath) {
        AuthoredCommands rootCommands = root.orElse(null);
        AuthoredCommands memberCommands = member.orElse(null);
        if (rootCommands != null && memberCommands != null) {
            for (LocalId id : rootCommandIds(rootCommands)) {
                if (memberCommands.tasks().containsKey(id)
                        || memberCommands.aliases().containsKey(id)) {
                    throw new IllegalArgumentException(
                            "Workspace command ID `" + id
                                    + "` cannot be redeclared by a member task or alias.");
                }
            }
        }
        Map<LocalId, EffectiveValue<AuthoredTask>> tasks = mergeNamed(
                root.map(AuthoredCommands::tasks).orElseGet(Map::of),
                member.map(AuthoredCommands::tasks).orElseGet(Map::of),
                rootManifestPath, memberManifestPath, "tasks", LocalId::value);
        Map<LocalId, EffectiveValue<AuthoredAlias>> aliases = mergeNamed(
                root.map(AuthoredCommands::aliases).orElseGet(Map::of),
                member.map(AuthoredCommands::aliases).orElseGet(Map::of),
                rootManifestPath, memberManifestPath, "aliases", LocalId::value);
        return new EffectiveCommands(tasks, aliases);
    }

    private static List<LocalId> rootCommandIds(AuthoredCommands commands) {
        return Stream.concat(
                        commands.tasks().keySet().stream(),
                        commands.aliases().keySet().stream())
                .toList();
    }

    private static void validateCredentialEnvironmentNames(
            Map<LocalId, EffectiveValue<RepositoryCredential>> credentials) {
        Map<LocalId, RepositoryCredential> values = credentials.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().value()));
        new AuthoredCredentials(values);
    }

    private static <T> EffectiveValue<T> inherited(EffectiveValue<T> value) {
        return value.origin() == ValueOrigin.BUILT_IN
                ? value
                : EffectiveValue.inherited(value.value(), value.source().orElseThrow());
    }
}
