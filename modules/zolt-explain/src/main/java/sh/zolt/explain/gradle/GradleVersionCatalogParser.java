package sh.zolt.explain.gradle;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.ExplainSignals;
import sh.zolt.explain.MigrationExplainException;
import sh.zolt.project.VersionPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.tomlj.Toml;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

final class GradleVersionCatalogParser {
    private static final List<String> RICH_VERSION_KEYS = List.of("prefer", "strictly", "require");
    private static final Set<String> DYNAMIC_RULES = Set.of("version-range", "dynamic-version");

    private GradleVersionCatalogParser() {
    }

    static List<GradleVersionCatalogAlias> parse(
            Path catalogPath,
            Map<String, String> aliases,
            Map<String, List<String>> bundles,
            Map<String, GradlePluginInspection> pluginAliases,
            List<ExplainSignal> signals) {
        if (!Files.isRegularFile(catalogPath)) {
            return List.of();
        }
        TomlParseResult result;
        try {
            result = Toml.parse(catalogPath);
        } catch (IOException exception) {
            throw new MigrationExplainException("Could not read Gradle version catalog for zolt explain: " + catalogPath, exception);
        }
        if (result.hasErrors()) {
            TomlParseError firstError = result.errors().getFirst();
            signals.add(ExplainSignals.GRADLE_VERSION_CATALOG_MALFORMED.signal(
                    ".",
                    "Gradle version catalog could not be parsed near " + firstError.position() + "."));
            return List.of();
        }

        Map<String, String> versions = parseVersions(result.getTable("versions"), signals);
        List<GradleVersionCatalogAlias> parsed = new ArrayList<>();
        Map<String, String> libraryCoordinatesByKey = new LinkedHashMap<>();
        TomlTable libraries = result.getTable("libraries");
        if (libraries != null) {
            for (String key : libraries.keySet()) {
                Optional<String> coordinate = libraryCoordinate(libraries, key, versions, signals);
                if (coordinate.isPresent()) {
                    String value = coordinate.orElseThrow();
                    libraryCoordinatesByKey.put(key, value);
                    aliases.put(key, value);
                    aliases.put(key.replace('-', '.'), value);
                    parsed.add(new GradleVersionCatalogAlias(key, value));
                }
            }
        }
        parseBundles(result, libraryCoordinatesByKey, bundles, signals);
        parsePlugins(result, versions, pluginAliases, signals);
        return parsed;
    }

    private static void parsePlugins(
            TomlParseResult result,
            Map<String, String> versions,
            Map<String, GradlePluginInspection> pluginAliases,
            List<ExplainSignal> signals) {
        TomlTable plugins = result.getTable("plugins");
        if (plugins == null) {
            return;
        }
        Map<String, Integer> normalizedAliasCounts = new LinkedHashMap<>();
        for (String key : plugins.keySet()) {
            normalizedAliasCounts.merge(normalizeAlias(key), 1, Integer::sum);
        }
        for (String key : plugins.keySet()) {
            String normalized = normalizeAlias(key);
            if (normalizedAliasCounts.getOrDefault(normalized, 0) != 1) {
                continue;
            }
            pluginAlias(plugins, key, versions, signals)
                    .ifPresent(plugin -> pluginAliases.put(normalized, plugin));
        }
    }

    private static Optional<GradlePluginInspection> pluginAlias(
            TomlTable plugins,
            String key,
            Map<String, String> versions,
            List<ExplainSignal> signals) {
        Object raw = plugins.get(key);
        if (raw instanceof String value && !value.isBlank()) {
            String[] parts = value.strip().split(":", -1);
            if (parts.length == 2) {
                String id = parts[0].strip();
                String version = parts[1].strip();
                if (!id.isBlank() && !version.isBlank()) {
                    return Optional.of(new GradlePluginInspection(id, version));
                }
            }
            return Optional.empty();
        }
        if (!(raw instanceof TomlTable table)) {
            return Optional.empty();
        }
        String id = nullToEmpty(table.getString("id")).strip();
        if (id.isBlank()) {
            return Optional.empty();
        }
        return pluginVersion("plugin alias `" + key + "`", table, versions, signals)
                .map(version -> new GradlePluginInspection(id, version));
    }

    private static String normalizeAlias(String alias) {
        return alias.replace('-', '.').replace('_', '.');
    }

    private static Map<String, String> parseVersions(TomlTable versionTable, List<ExplainSignal> signals) {
        Map<String, String> versions = new LinkedHashMap<>();
        if (versionTable == null) {
            return versions;
        }
        Map<String, Integer> normalizedAliasCounts = new LinkedHashMap<>();
        for (String key : versionTable.keySet()) {
            normalizedAliasCounts.merge(normalizeAlias(key), 1, Integer::sum);
        }
        for (String key : versionTable.keySet()) {
            String normalized = normalizeAlias(key);
            if (normalizedAliasCounts.getOrDefault(normalized, 0) != 1) {
                continue;
            }
            versionValue("version `" + key + "`", versionTable.get(key), signals)
                    .ifPresent(version -> versions.put(normalized, version));
        }
        return versions;
    }

    private static void parseBundles(
            TomlParseResult result,
            Map<String, String> libraryCoordinatesByKey,
            Map<String, List<String>> bundles,
            List<ExplainSignal> signals) {
        TomlTable bundleTable = result.getTable("bundles");
        if (bundleTable == null) {
            return;
        }
        for (String bundle : bundleTable.keySet()) {
            org.tomlj.TomlArray memberArray = bundleTable.getArray(List.of(bundle));
            List<?> members = memberArray == null ? List.of() : memberArray.toList();
            List<String> coordinates = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            for (Object member : members) {
                if (!(member instanceof String memberKey) || memberKey.isBlank()) {
                    continue;
                }
                String coordinate = libraryCoordinatesByKey.get(memberKey);
                if (coordinate == null) {
                    coordinate = libraryCoordinatesByKey.get(memberKey.replace('.', '-'));
                }
                if (coordinate == null) {
                    unresolved.add(memberKey);
                } else {
                    coordinates.add(coordinate);
                }
            }
            bundles.put(bundle, coordinates);
            bundles.put(bundle.replace('-', '.'), coordinates);
            if (!unresolved.isEmpty()) {
                signals.add(ExplainSignals.GRADLE_VERSION_CATALOG_BUNDLE_UNRESOLVED.signal(
                        ".",
                        "Gradle version catalog bundle `" + bundle + "` references undefined libraries "
                                + unresolved + "; those members were dropped from the migration draft."));
            }
        }
    }

    private static Optional<String> libraryCoordinate(
            TomlTable libraries,
            String key,
            Map<String, String> versions,
            List<ExplainSignal> signals) {
        Object raw = libraries.get(key);
        if (raw instanceof String value && !value.isBlank()) {
            return Optional.of(value);
        }
        if (!(raw instanceof TomlTable table)) {
            return Optional.empty();
        }
        String module = nullToEmpty(table.getString("module"));
        if (module.isBlank()) {
            String group = nullToEmpty(table.getString("group"));
            String name = nullToEmpty(table.getString("name"));
            if (!group.isBlank() && !name.isBlank()) {
                module = group + ":" + name;
            }
        }
        if (module.isBlank()) {
            return Optional.empty();
        }
        String resolvedModule = module;
        return libraryVersion(key, table, versions, signals)
                .map(version -> version.isBlank() ? resolvedModule : resolvedModule + ":" + version);
    }

    private static Optional<String> libraryVersion(
            String key,
            TomlTable table,
            Map<String, String> versions,
            List<ExplainSignal> signals) {
        Object rawVersion = table.get("version");
        if (rawVersion instanceof String stringVersion && !stringVersion.isBlank()) {
            return Optional.of(stringVersion);
        }

        String ref = nullToEmpty(table.getString("version.ref")).strip();
        TomlTable versionTable = table.getTable("version");
        if (ref.isBlank() && versionTable != null) {
            ref = nullToEmpty(versionTable.getString("ref")).strip();
        }
        if (!ref.isBlank()) {
            return Optional.ofNullable(versions.get(normalizeAlias(ref)))
                    .filter(version -> !version.isBlank());
        }
        if (versionTable != null) {
            return richVersionValue("alias `" + key + "`", versionTable, signals);
        }
        return rawVersion == null ? Optional.of("") : Optional.empty();
    }

    private static Optional<String> pluginVersion(
            String owner,
            TomlTable table,
            Map<String, String> versions,
            List<ExplainSignal> signals) {
        Object rawVersion = table.get("version");
        if (rawVersion instanceof String stringVersion && !stringVersion.isBlank()) {
            return Optional.of(stringVersion.strip());
        }

        String ref = nullToEmpty(table.getString("version.ref")).strip();
        TomlTable versionTable = table.getTable("version");
        if (ref.isBlank() && versionTable != null) {
            ref = nullToEmpty(versionTable.getString("ref")).strip();
        }
        if (!ref.isBlank()) {
            return Optional.ofNullable(versions.get(normalizeAlias(ref)))
                    .filter(version -> !version.isBlank());
        }
        if (versionTable != null) {
            return richVersionValue(owner, versionTable, signals);
        }
        return rawVersion == null ? Optional.of("") : Optional.empty();
    }

    private static Optional<String> versionValue(String owner, Object raw, List<ExplainSignal> signals) {
        if (raw instanceof String value && !value.isBlank()) {
            return Optional.of(value);
        }
        if (raw instanceof TomlTable table) {
            return richVersionValue(owner, table, signals);
        }
        return Optional.empty();
    }

    private static Optional<String> richVersionValue(String owner, TomlTable table, List<ExplainSignal> signals) {
        for (String key : RICH_VERSION_KEYS) {
            String value = nullToEmpty(table.getString(key)).strip();
            dynamicVersionRule(value).ifPresent(rule -> signals.add(ExplainSignals.GRADLE_DEPENDENCY_DYNAMIC_VERSION.signal(
                    ".",
                    "Gradle version catalog " + owner + " uses dynamic version `" + value
                            + "` in `" + key + "` (version-policy rule: " + rule + ").")));
        }
        for (String key : RICH_VERSION_KEYS) {
            String value = nullToEmpty(table.getString(key)).strip();
            if (!value.isBlank() && dynamicVersionRule(value).isEmpty()) {
                return Optional.of(value);
            }
        }
        for (String key : RICH_VERSION_KEYS) {
            String value = nullToEmpty(table.getString(key)).strip();
            if (!value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static Optional<String> dynamicVersionRule(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        if (isGradleVersionRange(version)) {
            return Optional.of("version-range");
        }
        return VersionPolicy.violation(VersionPolicy.Context.EXTERNAL_DEPENDENCY, version)
                .map(VersionPolicy.Violation::rule)
                .filter(DYNAMIC_RULES::contains);
    }

    private static boolean isGradleVersionRange(String version) {
        return version.contains(",")
                && (version.startsWith("[") || version.startsWith("(") || version.startsWith("]"))
                && (version.endsWith("]") || version.endsWith(")") || version.endsWith("["));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
