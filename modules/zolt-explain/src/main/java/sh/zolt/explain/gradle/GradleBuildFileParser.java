package sh.zolt.explain.gradle;

import sh.zolt.explain.ExplainSignal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GradleBuildFileParser {
    private static final Pattern REPOSITORY_URL_PATTERN = Pattern.compile("\\burl\\s*(?:=\\s*)?(?:uri\\s*\\(\\s*)?['\"]([^'\"]+)['\"]");
    private static final Pattern QUOTED_PATTERN = Pattern.compile("['\"]([^'\"]+)['\"]");
    private final GradlePluginBlockParser pluginBlockParser = new GradlePluginBlockParser();

    List<GradlePluginInspection> plugins(String content) {
        return pluginBlockParser.plugins(content, Map.of(), ".", new ArrayList<>());
    }

    List<GradlePluginInspection> plugins(
            String content,
            Map<String, GradlePluginInspection> catalogPlugins,
            String project,
            List<ExplainSignal> signals) {
        return pluginBlockParser.plugins(content, catalogPlugins, project, signals);
    }

    List<GradleRepositoryInspection> repositories(String content) {
        return repositoriesFromBlocks(repositoryBlocks(content));
    }

    List<String> repositoryBlocks(String content) {
        return GradleScriptBlocks.topLevelBlocks(content, "repositories");
    }

    List<GradleRepositoryInspection> settingsRepositories(String content) {
        return repositoriesFromBlocks(GradleScriptBlocks.blocksAtPath(
                content,
                List.of("dependencyResolutionManagement", "repositories")));
    }

    private static List<GradleRepositoryInspection> repositoriesFromBlocks(List<String> blocks) {
        List<GradleRepositoryInspection> repositories = new ArrayList<>();
        for (String block : blocks) {
            if (block.contains("mavenCentral()")) {
                addRepository(repositories, new GradleRepositoryInspection("mavenCentral", "https://repo.maven.apache.org/maven2"));
            }
            if (block.contains("gradlePluginPortal()")) {
                addRepository(repositories, new GradleRepositoryInspection("gradlePluginPortal", "https://plugins.gradle.org/m2"));
            }
            if (block.contains("google()")) {
                addRepository(repositories, new GradleRepositoryInspection("google", "https://dl.google.com/dl/android/maven2"));
            }
            if (block.contains("mavenLocal()")) {
                addRepository(repositories, new GradleRepositoryInspection("mavenLocal", "~/.m2/repository"));
            }
            Matcher urlMatcher = REPOSITORY_URL_PATTERN.matcher(block);
            while (urlMatcher.find()) {
                addRepository(repositories, new GradleRepositoryInspection("maven", urlMatcher.group(1)));
            }
        }
        repositories.sort(Comparator.comparing(GradleRepositoryInspection::kind).thenComparing(GradleRepositoryInspection::url));
        return repositories;
    }

    private static void addRepository(List<GradleRepositoryInspection> repositories, GradleRepositoryInspection repository) {
        if (!repositories.contains(repository)) {
            repositories.add(repository);
        }
    }

    List<GradleDependencyInspection> dependencies(
            String content,
            Map<String, String> versionCatalog,
            Map<String, List<String>> catalogBundles,
            String project,
            List<ExplainSignal> signals) {
        return GradleDependencyParser.dependencies(content, versionCatalog, catalogBundles, Map.of(), project, signals);
    }

    List<GradleDependencyInspection> dependencies(
            String content,
            Map<String, String> versionCatalog,
            Map<String, List<String>> catalogBundles,
            Map<String, String> properties,
            String project,
            List<ExplainSignal> signals) {
        return GradleDependencyParser.dependencies(content, versionCatalog, catalogBundles, properties, project, signals);
    }

    List<GradleDependencyInspection> constraints(
            String content,
            Map<String, String> versionCatalog,
            Map<String, List<String>> catalogBundles,
            Map<String, String> properties,
            String project,
            List<ExplainSignal> signals) {
        return GradleDependencyParser.constraints(content, versionCatalog, catalogBundles, properties, project, signals);
    }

    Map<String, String> extProperties(String content) {
        return GradleDependencyParser.extProperties(content);
    }

    String javaVersion(String content) {
        for (Pattern pattern : List.of(
                Pattern.compile("\\blanguageVersion\\s*(?:=\\s*|\\.set\\s*\\(\\s*)"
                        + "JavaLanguageVersion\\.of\\s*\\(\\s*(\\d+)\\s*\\)"),
                Pattern.compile("\\bsourceCompatibility\\s*=\\s*JavaVersion\\.VERSION_([0-9_]+)"),
                Pattern.compile("\\btargetCompatibility\\s*=\\s*JavaVersion\\.VERSION_([0-9_]+)"),
                Pattern.compile("\\bsourceCompatibility\\s*=\\s*['\"]([^'\"]+)['\"]"),
                Pattern.compile("\\btargetCompatibility\\s*=\\s*['\"]([^'\"]+)['\"]"),
                Pattern.compile("\\bsourceCompatibility\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)"),
                Pattern.compile("\\btargetCompatibility\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)"))) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1).replace('_', '.');
            }
        }
        return "unknown";
    }

    Optional<String> group(String content) {
        return topLevelStringAssignment(content, "group");
    }

    Optional<String> version(String content) {
        return topLevelStringAssignment(content, "version");
    }

    Optional<String> mainClass(String content) {
        Optional<String> topLevel = firstQuotedAfter(GradleScriptBlocks.withoutNestedBlocks(content), List.of(
                Pattern.compile("\\bmainClass\\s*(?:=|\\.set\\s*\\(|\\.value\\s*\\()\\s*['\"]([^'\"]+)['\"]"),
                Pattern.compile("\\bmainClassName\\s*=\\s*['\"]([^'\"]+)['\"]")));
        if (topLevel.isPresent()) {
            return topLevel;
        }
        return GradleScriptBlocks.topLevelBlock(content, "application")
                .flatMap(block -> firstQuotedAfter(block, List.of(
                        Pattern.compile("\\bmainClass\\s*(?:=|\\.set\\s*\\(|\\.value\\s*\\()\\s*['\"]([^'\"]+)['\"]"),
                        Pattern.compile("\\bmainClassName\\s*=\\s*['\"]([^'\"]+)['\"]"))));
    }

    /**
     * Reads a Groovy/Kotlin-DSL string assignment such as {@code group = 'com.example'} or
     * {@code version "0.3.1"}. Only literal single/double-quoted values are returned; interpolated
     * or computed values (e.g. {@code group "${applicationGroupId}"}) are treated as absent so the
     * draft falls back to a placeholder rather than emitting a broken coordinate.
     */
    private static Optional<String> stringAssignment(String content, String property) {
        return firstQuotedAfter(content, List.of(
                Pattern.compile("(?m)^[\\t ]*" + Pattern.quote(property) + "\\s*=\\s*['\"]([^'\"$]+)['\"]"),
                Pattern.compile("(?m)^[\\t ]*" + Pattern.quote(property) + "\\s+['\"]([^'\"$]+)['\"]")));
    }

    private static Optional<String> topLevelStringAssignment(String content, String property) {
        return stringAssignment(GradleScriptBlocks.withoutNestedBlocks(content), property);
    }

    private static Optional<String> firstQuotedAfter(String content, List<Pattern> patterns) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                String value = matcher.group(1).strip();
                if (!value.isBlank()) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    List<String> sourceRoots(String content, String sourceSet, String defaultRoot) {
        return sourceRoots(content, sourceSet, defaultRoot, true);
    }

    List<String> sourceRoots(String content, String sourceSet, String defaultRoot, boolean includeDefaultRoot) {
        Optional<String> sourceSets = GradleScriptBlocks.topLevelBlock(content, "sourceSets");
        if (sourceSets.isEmpty()) {
            return defaultRoots(defaultRoot, includeDefaultRoot);
        }
        Optional<String> sourceSetBlock = GradleScriptBlocks.topLevelBlock(sourceSets.orElseThrow(), sourceSet);
        if (sourceSetBlock.isEmpty()) {
            return defaultRoots(defaultRoot, includeDefaultRoot);
        }
        List<String> roots = new ArrayList<>();
        if (includeDefaultRoot && containsAny(sourceSetBlock.orElseThrow(), "srcDirs +=", "srcDir(")) {
            roots.add(defaultRoot);
        }
        Matcher srcDirs = Pattern.compile("\\bsrcDirs?\\s*(?:=\\s*)?(?:\\[([^]]*)]|([^\\n]+))").matcher(sourceSetBlock.orElseThrow());
        while (srcDirs.find()) {
            String value = srcDirs.group(1) == null ? srcDirs.group(2) : srcDirs.group(1);
            roots.addAll(quotedValues(value));
        }
        return roots.isEmpty() ? defaultRoots(defaultRoot, includeDefaultRoot) : roots.stream().distinct().toList();
    }

    private static List<String> defaultRoots(String defaultRoot, boolean includeDefaultRoot) {
        return includeDefaultRoot ? List.of(defaultRoot) : List.of();
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

    private static boolean containsAny(String content, String... values) {
        for (String value : values) {
            if (content.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

}
