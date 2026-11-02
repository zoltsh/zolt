package sh.zolt.explain.gradle;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.ExplainSignals;
import sh.zolt.project.VersionPolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GradleDependencyParser {
    private static final List<String> DEPENDENCY_CONFIGURATIONS = List.of(
            "api",
            "implementation",
            "compileOnly",
            "runtimeOnly",
            "testImplementation",
            "testCompileOnly",
            "testRuntimeOnly",
            "annotationProcessor",
            "testAnnotationProcessor");
    // The java-platform plugin declares constraints only in its api/runtime configurations.
    private static final List<String> CONSTRAINT_CONFIGURATIONS = List.of("api", "runtime");
    private static final Pattern PROPERTY_PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_.-]*)}|\\$([A-Za-z][A-Za-z0-9_.-]*)");
    private static final Pattern QUOTED_PATTERN = Pattern.compile("['\"]([^'\"]+)['\"]");
    private static final Pattern PLATFORM_WRAPPER =
            Pattern.compile("^\\s*(enforcedPlatform|platform)\\s*\\(");

    private GradleDependencyParser() {
    }

    static List<GradleDependencyInspection> dependencies(
            String content,
            Map<String, String> versionCatalog,
            Map<String, List<String>> catalogBundles,
            Map<String, String> properties,
            String project,
            List<ExplainSignal> signals) {
        return parseBlocks(
                GradleScriptBlocks.topLevelBlocks(content, "dependencies"),
                DEPENDENCY_CONFIGURATIONS,
                versionCatalog,
                catalogBundles,
                properties,
                project,
                signals);
    }

    /**
     * Parses the {@code api}/{@code runtime} pins declared inside a {@code dependencies { constraints { } }}
     * block. The outer dependency parser blanks nested blocks, so constraints are otherwise invisible; a
     * {@code java-platform} producer routes these into {@code [bom.versions]}. Interpolated or otherwise
     * unresolvable pins raise the same signals as regular dependencies rather than vanishing.
     */
    static List<GradleDependencyInspection> constraints(
            String content,
            Map<String, String> versionCatalog,
            Map<String, List<String>> catalogBundles,
            Map<String, String> properties,
            String project,
            List<ExplainSignal> signals) {
        return parseBlocks(
                GradleScriptBlocks.blocksAtPath(content, List.of("dependencies", "constraints")),
                CONSTRAINT_CONFIGURATIONS,
                versionCatalog,
                catalogBundles,
                properties,
                project,
                signals);
    }

    private static List<GradleDependencyInspection> parseBlocks(
            List<String> blocks,
            List<String> configurations,
            Map<String, String> versionCatalog,
            Map<String, List<String>> catalogBundles,
            Map<String, String> properties,
            String project,
            List<ExplainSignal> signals) {
        List<GradleDependencyInspection> dependencies = new ArrayList<>();
        for (String configuration : configurations) {
            Pattern pattern = Pattern.compile(
                    "(?m)(?:^|[{;])[\\t ]*" + Pattern.quote(configuration) + "(?![A-Za-z0-9_])\\s*(?:\\(([^\\n]+?)\\)|([^\\n]+))");
            for (String block : blocks) {
                String topLevelStatements = GradleScriptBlocks.withoutNestedBlocks(block);
                Matcher matcher = pattern.matcher(topLevelStatements);
                while (matcher.find()) {
                    String expression = (matcher.group(1) == null ? matcher.group(2) : matcher.group(1)).trim();
                    dependencies.addAll(dependencies(
                            configuration,
                            expression,
                            versionCatalog,
                            catalogBundles,
                            properties,
                            project,
                            signals));
                }
            }
        }
        dependencies.sort(Comparator
                .comparing(GradleDependencyInspection::configuration)
                .thenComparing(GradleDependencyInspection::notation)
                .thenComparing(GradleDependencyInspection::resolvedCoordinate));
        return dependencies;
    }

    static Map<String, String> extProperties(String content) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (String block : GradleScriptBlocks.topLevelBlocks(content, "ext")) {
            Matcher matcher = Pattern.compile("(?m)^[\\t ]*([A-Za-z][A-Za-z0-9_.-]*)\\s*=\\s*['\"]([^'\"$]+)['\"]")
                    .matcher(block);
            while (matcher.find()) {
                properties.put(matcher.group(1), matcher.group(2).strip());
            }
        }
        return properties;
    }

    private static List<GradleDependencyInspection> dependencies(
            String configuration,
            String expression,
            Map<String, String> versionCatalog,
            Map<String, List<String>> catalogBundles,
            Map<String, String> properties,
            String project,
            List<ExplainSignal> signals) {
        String notation = expression.replaceAll("\\s+", " ").strip();
        Optional<String> interpolatedNotation = interpolate(notation, properties);
        if (interpolatedNotation.isEmpty()) {
            unresolvedInterpolationSignal(project, notation, signals);
            return List.of();
        }
        notation = interpolatedNotation.orElseThrow();
        // A platform(...) / enforcedPlatform(...) wrapper marks a scope-agnostic BOM import; the inner
        // coordinate/alias is extracted by the same machinery below, tagged so the emitter routes it to
        // [platforms] (or [bom.imports]) rather than a regular dependency section.
        GradleDependencyInspection.PlatformKind platformKind = platformKind(notation);
        Optional<String> mapNotation = mapNotation(notation);
        if (mapNotation.isPresent()) {
            return List.of(new GradleDependencyInspection(
                    configuration, mapNotation.orElseThrow(), mapNotation.orElseThrow(), "", platformKind));
        }
        List<String> quoted = quotedValues(notation);
        if (!quoted.isEmpty()) {
            return quoted.stream()
                    .map(value -> new GradleDependencyInspection(configuration, value, value, "", platformKind))
                    .toList();
        }
        Optional<String> bundle = catalogBundleAlias(notation);
        if (bundle.isPresent()) {
            return bundleDependencies(configuration, notation, bundle.orElseThrow(), catalogBundles, project, signals);
        }
        List<String> aliases = catalogAliases(notation);
        if (!aliases.isEmpty()) {
            String catalogNotation = notation;
            List<GradleDependencyInspection> catalogDependencies = new ArrayList<>();
            for (String key : aliases) {
                String coordinate = versionCatalog.get(key);
                if (coordinate == null || coordinate.isBlank()) {
                    unresolvedDependencySignal(project, configuration, catalogNotation, signals);
                    coordinate = "";
                }
                catalogDependencies.add(new GradleDependencyInspection(
                        configuration, catalogNotation, coordinate, key, platformKind));
            }
            return catalogDependencies;
        }
        unresolvedDependencySignal(project, configuration, notation, signals);
        return List.of();
    }

    private static Optional<String> interpolate(String value, Map<String, String> properties) {
        Matcher matcher = PROPERTY_PLACEHOLDER.matcher(value);
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        boolean changed = false;
        while (matcher.find()) {
            changed = true;
            String name = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            String replacement = properties.get(name);
            if (replacement == null || replacement.isBlank()) {
                return Optional.empty();
            }
            out.append(value, cursor, matcher.start());
            out.append(replacement);
            cursor = matcher.end();
        }
        if (!changed) {
            return Optional.of(value);
        }
        out.append(value, cursor, value.length());
        return Optional.of(out.toString());
    }

    private static void unresolvedInterpolationSignal(String project, String notation, List<ExplainSignal> signals) {
        String version = firstPlaceholder(notation).orElse(notation);
        String rule = VersionPolicy.violation(VersionPolicy.Context.EXTERNAL_DEPENDENCY, version)
                .map(VersionPolicy.Violation::rule)
                .orElse("no-interpolation");
        signals.add(ExplainSignals.GRADLE_DEPENDENCY_DYNAMIC_VERSION.signal(
                project,
                "Dependency `" + notation + "` uses dynamic version `" + version
                        + "` (version-policy rule: " + rule + ")."));
    }

    private static Optional<String> firstPlaceholder(String value) {
        Matcher matcher = PROPERTY_PLACEHOLDER.matcher(value);
        return matcher.find() ? Optional.of(matcher.group()) : Optional.empty();
    }

    private static void unresolvedDependencySignal(
            String project,
            String configuration,
            String notation,
            List<ExplainSignal> signals) {
        if (notation.isBlank()) {
            return;
        }
        signals.add(ExplainSignals.GRADLE_DEPENDENCY_UNRESOLVED_NOTATION.signal(
                project,
                "Gradle dependency `" + configuration + " " + notation
                        + "` is not a statically resolvable literal and was not emitted as a dependency."));
    }

    private static List<GradleDependencyInspection> bundleDependencies(
            String configuration,
            String notation,
            String bundleName,
            Map<String, List<String>> catalogBundles,
            String project,
            List<ExplainSignal> signals) {
        List<String> members = catalogBundles.get(bundleName);
        if (members == null) {
            signals.add(ExplainSignals.GRADLE_VERSION_CATALOG_BUNDLE_UNRESOLVED.signal(
                    project,
                    "Gradle dependency references version-catalog bundle `" + bundleName
                            + "`, which is not defined in gradle/libs.versions.toml."));
            return List.of(new GradleDependencyInspection(configuration, notation, "", "bundles." + bundleName));
        }
        if (members.isEmpty()) {
            return List.of(new GradleDependencyInspection(configuration, notation, "", "bundles." + bundleName));
        }
        List<GradleDependencyInspection> expanded = new ArrayList<>();
        for (String coordinate : members) {
            expanded.add(new GradleDependencyInspection(configuration, notation, coordinate, "bundles." + bundleName));
        }
        return expanded;
    }

    private static GradleDependencyInspection.PlatformKind platformKind(String notation) {
        Matcher matcher = PLATFORM_WRAPPER.matcher(notation);
        if (!matcher.find()) {
            return GradleDependencyInspection.PlatformKind.NONE;
        }
        return "enforcedPlatform".equals(matcher.group(1))
                ? GradleDependencyInspection.PlatformKind.ENFORCED_PLATFORM
                : GradleDependencyInspection.PlatformKind.PLATFORM;
    }

    private static Optional<String> mapNotation(String value) {
        String group = namedArgument(value, "group");
        String name = namedArgument(value, "name");
        String version = namedArgument(value, "version");
        if (group.isBlank() || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(version.isBlank() ? group + ":" + name : group + ":" + name + ":" + version);
    }

    private static String namedArgument(String expression, String name) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*(?::|=)\\s*['\"]([^'\"]+)['\"]").matcher(expression);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static List<String> catalogAliases(String expression) {
        Matcher matcher = Pattern.compile("\\blibs\\.(?!bundles\\.)([A-Za-z0-9_.-]+)").matcher(expression);
        List<String> aliases = new ArrayList<>();
        while (matcher.find()) {
            aliases.add(matcher.group(1));
        }
        return aliases;
    }

    private static Optional<String> catalogBundleAlias(String expression) {
        Matcher matcher = Pattern.compile("\\blibs\\.bundles\\.([A-Za-z0-9_.-]+)").matcher(expression);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static List<String> quotedValues(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Matcher matcher = QUOTED_PATTERN.matcher(input);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return values;
    }
}
