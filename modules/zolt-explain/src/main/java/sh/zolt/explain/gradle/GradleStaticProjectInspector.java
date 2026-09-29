package sh.zolt.explain.gradle;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.ExplainSignals;
import sh.zolt.explain.MigrationExplainException;
import sh.zolt.explain.SourceTreeEvidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class GradleStaticProjectInspector {
    private static final Pattern SOURCE_SETS_CONFIGURATION = Pattern.compile("\\bsourceSets\\b");

    private final GradleBuildFileParser buildFileParser = new GradleBuildFileParser();
    private final GradleMigrationSignalDetector signalDetector = new GradleMigrationSignalDetector();

    public GradleInspectionResult inspect(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Optional<Path> settingsFile = firstRegularFile(
                normalizedRoot.resolve("settings.gradle"),
                normalizedRoot.resolve("settings.gradle.kts"));
        Optional<Path> rootBuildFile = buildFile(normalizedRoot);
        if (settingsFile.isEmpty() && rootBuildFile.isEmpty()) {
            throw new MigrationExplainException(
                    "Could not inspect Gradle project. Expected settings.gradle, settings.gradle.kts, build.gradle, or build.gradle.kts at "
                            + normalizedRoot
                            + ". Run zolt explain from a Gradle project root or pass --cwd.");
        }

        List<ExplainSignal> signals = new ArrayList<>();
        Map<String, String> versionCatalog = new LinkedHashMap<>();
        Map<String, List<String>> catalogBundles = new LinkedHashMap<>();
        Map<String, GradlePluginInspection> catalogPlugins = new LinkedHashMap<>();
        List<GradleVersionCatalogAlias> aliases = new ArrayList<>(GradleVersionCatalogParser.parse(
                normalizedRoot.resolve("gradle/libs.versions.toml"),
                versionCatalog,
                catalogBundles,
                catalogPlugins,
                signals));
        String settingsContent = settingsFile.map(GradleStaticProjectInspector::read).orElse("");
        boolean kotlinSettingsShape = GradleKotlinProjectEvidence
                .standaloneSettingsShapeProven(settingsContent);
        List<String> includedProjects = settingsFile.isPresent()
                ? GradleSettingsScripts.includedProjects(settingsContent)
                : List.of();
        Optional<String> rootProjectName = settingsFile.isPresent()
                ? GradleSettingsScripts.rootProjectName(settingsContent)
                : Optional.empty();
        GradleSettingsBuildFileNames settingsBuildFileNames = GradleSettingsBuildFileNames.parse(settingsContent);
        settingsFile.ifPresent(path -> signals.addAll(GradleSettingsScripts.signals(normalizedRoot, path, settingsContent)));
        if (Files.isDirectory(normalizedRoot.resolve("buildSrc"))) {
            signals.add(ExplainSignals.GRADLE_BUILD_SRC_DETECTED.signal(
                    ".",
                    "Gradle buildSrc is present."));
        }
        Map<String, String> rootProperties = GradleProperties.read(normalizedRoot.resolve("gradle.properties"));

        List<GradleProjectInspection> projects = new ArrayList<>();
        rootBuildFile.ifPresent(path -> projects.add(inspectProject(
                normalizedRoot,
                normalizedRoot,
                path,
                rootProjectName,
                rootProperties,
                buildFileParser.settingsRepositories(settingsContent),
                kotlinSettingsShape,
                versionCatalog,
                catalogBundles,
                catalogPlugins,
                signals)));
        for (String includedProject : includedProjects) {
            Path projectDirectory = normalizedRoot.resolve(includedProject).normalize();
            String projectName = projectDirectory.getFileName().toString();
            Optional<Path> includedBuildFile = settingsBuildFileNames.buildFile(projectDirectory, projectName);
            if (includedBuildFile.isPresent()) {
                projects.add(inspectProject(
                        normalizedRoot,
                        projectDirectory,
                        includedBuildFile.orElseThrow(),
                        Optional.empty(),
                        rootProperties,
                        List.of(),
                        kotlinSettingsShape,
                        versionCatalog,
                        catalogBundles,
                        catalogPlugins,
                        signals));
                continue;
            }
            Optional<ExplainSignal> unresolvedBuildFileName = settingsBuildFileNames.unresolvedCandidateSignal(
                    normalizedRoot,
                    projectDirectory,
                    includedProject);
            if (unresolvedBuildFileName.isPresent()) {
                signals.add(unresolvedBuildFileName.orElseThrow());
            } else {
                String expected = settingsBuildFileNames.fileNameFor(projectName)
                        .map(name -> "`" + name + "`")
                        .orElse("build.gradle or build.gradle.kts");
                signals.add(ExplainSignals.GRADLE_PROJECT_MISSING_BUILD_FILE.signal(
                        projectLabel(normalizedRoot, projectDirectory),
                        "Included Gradle project `" + includedProject + "` does not contain " + expected + "."));
            }
        }

        projects.sort(Comparator.comparing(project -> project.path().toString()));
        aliases.sort(Comparator.comparing(GradleVersionCatalogAlias::alias));
        return new GradleInspectionResult(
                normalizedRoot,
                settingsFile.map(path -> normalizedRoot.relativize(path).toString()).orElse(""),
                includedProjects.stream().sorted().toList(),
                aliases,
                projects,
                ExplainSignals.sorted(signals));
    }

    private GradleProjectInspection inspectProject(
            Path root,
            Path projectDirectory,
            Path buildFile,
            Optional<String> declaredName,
            Map<String, String> rootProperties,
            List<GradleRepositoryInspection> settingsRepositories,
            boolean kotlinSettingsShape,
            Map<String, String> versionCatalog,
            Map<String, List<String>> catalogBundles,
            Map<String, GradlePluginInspection> catalogPlugins,
            List<ExplainSignal> signals) {
        String content = stripComments(read(buildFile));
        Map<String, String> projectProperties = GradleProperties.read(projectDirectory.resolve("gradle.properties"));
        Path relativePath = relativePath(root, projectDirectory);
        String project = path(relativePath);
        List<GradlePluginInspection> plugins = buildFileParser.plugins(
                content, catalogPlugins, project, signals);
        Map<String, String> dependencyProperties = new LinkedHashMap<>(rootProperties);
        dependencyProperties.putAll(projectProperties);
        dependencyProperties.putAll(buildFileParser.extProperties(content));
        List<GradleDependencyInspection> dependencies =
                buildFileParser.dependencies(content, versionCatalog, catalogBundles, dependencyProperties, project, signals);
        boolean javaPlatform = plugins.stream().anyMatch(plugin ->
                plugin.applied() && "java-platform".equals(plugin.id()));
        List<GradleDependencyInspection> constraints = javaPlatform
                ? buildFileParser.constraints(content, versionCatalog, catalogBundles, dependencyProperties, project, signals)
                : List.of();
        signals.addAll(signalDetector.signals(project, content, dependencies, plugins));
        boolean kotlinConvention = usesKotlinJvmConvention(content, plugins);
        if (javaPlatform) {
            long imports = dependencies.stream().filter(GradleDependencyInspection::isPlatform).count();
            signals.add(ExplainSignals.GRADLE_BOM_DETECTED.signal(
                    project,
                    "java-platform BOM detected: " + constraints.size() + " version constraint(s), "
                            + imports + " platform import(s)."));
        }
        List<String> mainSourceRoots = mainSourceRoots(projectDirectory, content, kotlinConvention);
        List<String> testSourceRoots = testSourceRoots(projectDirectory, content, kotlinConvention);
        List<String> groovyTestSourceRoots = buildFileParser.sourceRoots(
                content,
                "test",
                "src/test/groovy",
                hasGroovyTestSources(projectDirectory, content));
        SourceTreeEvidence sourceTree = SourceTreeEvidence.inspect(
                "Gradle",
                projectDirectory,
                mainSourceRoots,
                testSourceRoots);
        return new GradleProjectInspection(
                relativePath,
                declaredName.filter(name -> !name.isBlank()).orElseGet(() -> projectDirectory.getFileName().toString()),
                buildFile.getFileName().toString(),
                buildFile.getFileName().toString().endsWith(".kts") ? "kotlin" : "groovy",
                buildFileParser.javaVersion(content),
                buildFileParser.group(content).or(() -> GradleProperties.value("group", projectProperties, rootProperties)),
                buildFileParser.version(content).or(() -> GradleProperties.value("version", projectProperties, rootProperties)),
                buildFileParser.mainClass(content),
                plugins,
                repositories(content, settingsRepositories),
                dependencies,
                mainSourceRoots,
                testSourceRoots,
                groovyTestSourceRoots,
                constraints,
                GradleKotlinProjectEvidence.inspect(
                        content,
                        rootProperties,
                        projectProperties,
                        Files.isDirectory(root.resolve("buildSrc")),
                        declaredName.isPresent(),
                        kotlinSettingsShape,
                        sourceTree));
    }

    private List<GradleRepositoryInspection> repositories(
            String buildFileContent,
            List<GradleRepositoryInspection> settingsRepositories) {
        List<GradleRepositoryInspection> repositories = new ArrayList<>(buildFileParser.repositories(buildFileContent));
        for (GradleRepositoryInspection repository : settingsRepositories) {
            if (!repositories.contains(repository)) {
                repositories.add(repository);
            }
        }
        repositories.sort(Comparator.comparing(GradleRepositoryInspection::kind).thenComparing(GradleRepositoryInspection::url));
        return repositories;
    }

    private static Optional<Path> buildFile(Path directory) {
        return firstRegularFile(directory.resolve("build.gradle"), directory.resolve("build.gradle.kts"));
    }

    private static Optional<Path> firstRegularFile(Path... paths) {
        for (Path path : paths) {
            if (Files.isRegularFile(path)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new MigrationExplainException("Could not read Gradle metadata for zolt explain: " + path, exception);
        }
    }

    private static String stripComments(String content) {
        return GradleSourceComments.stripComments(content);
    }

    private static Path relativePath(Path root, Path projectDirectory) {
        Path relative = root.relativize(projectDirectory);
        return relative.toString().isBlank() ? Path.of(".") : relative;
    }

    private static String projectLabel(Path root, Path projectDirectory) {
        return path(relativePath(root, projectDirectory));
    }

    private static String path(Path path) {
        return path.toString().replace('\\', '/');
    }

    private List<String> mainSourceRoots(
            Path projectDirectory,
            String content,
            boolean kotlinConvention) {
        List<String> roots = new ArrayList<>(buildFileParser.sourceRoots(
                content,
                "main",
                "src/main/java",
                Files.isDirectory(projectDirectory.resolve("src/main/java"))));
        if (Files.isDirectory(projectDirectory.resolve("src/main/groovy"))) {
            roots.add("src/main/groovy");
        }
        if (kotlinConvention && Files.isDirectory(projectDirectory.resolve("src/main/kotlin"))) {
            roots.add("src/main/kotlin");
        }
        return roots.stream().distinct().toList();
    }

    private List<String> testSourceRoots(
            Path projectDirectory,
            String content,
            boolean kotlinConvention) {
        List<String> roots = new ArrayList<>(buildFileParser.sourceRoots(
                content,
                "test",
                "src/test/java",
                Files.isDirectory(projectDirectory.resolve("src/test/java"))));
        if (kotlinConvention && Files.isDirectory(projectDirectory.resolve("src/test/kotlin"))) {
            roots.add("src/test/kotlin");
        }
        return roots.stream().distinct().toList();
    }

    private static boolean usesKotlinJvmConvention(
            String content,
            List<GradlePluginInspection> plugins) {
        Optional<String> pluginsBlock = GradleScriptBlocks.topLevelBlock(content, "plugins");
        if (pluginsBlock.isEmpty()
                || SOURCE_SETS_CONFIGURATION.matcher(content).find()) {
            return false;
        }
        return plugins.stream()
                .filter(GradlePluginInspection::applied)
                .map(GradlePluginInspection::id)
                .anyMatch(id -> id.equals("org.jetbrains.kotlin.jvm") || id.equals("kotlin"));
    }

    private static boolean hasGroovyTestSources(Path projectDirectory, String content) {
        return Files.isDirectory(projectDirectory.resolve("src/test/groovy"))
                || content.contains("src/test/groovy");
    }

}
