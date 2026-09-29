package sh.zolt.explain.gradle;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.ExplainSignals;
import sh.zolt.project.VersionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

final class GradleMigrationSignalDetector {
    List<ExplainSignal> signals(
            String project,
            String content,
            List<GradleDependencyInspection> dependencies,
            List<GradlePluginInspection> plugins) {
        List<ExplainSignal> signals = new ArrayList<>();
        signals.addAll(conventionPluginSignals(project, plugins));
        signals.addAll(unsupportedPluginSignals(project, content, plugins));
        signals.addAll(dynamicSignals(project, content));
        signals.addAll(dependencySignals(project, dependencies));
        signals.addAll(pluginSignals(project, plugins));
        signals.addAll(enterpriseSignals(project, content));
        signals.addAll(execTaskSignals(project, content));
        return signals;
    }

    private static List<ExplainSignal> execTaskSignals(String project, String content) {
        List<ExplainSignal> signals = new ArrayList<>();
        for (GradleSignalPatterns.ExecTask task : GradleSignalPatterns.execTasks(content)) {
            if (task.unmappable()) {
                signals.add(ExplainSignals.GRADLE_EXEC_UNMAPPABLE.signal(
                        project,
                        "Gradle " + task.type() + " task uses task actions, control flow, or a shell command"
                                + " outside the single-command exec surface."));
            } else {
                signals.add(ExplainSignals.GRADLE_EXEC_MAPPABLE.signal(
                        project,
                        "Gradle " + task.type() + " task runs a single command that maps to a Zolt exec step."));
            }
        }
        return signals;
    }

    private static List<ExplainSignal> conventionPluginSignals(String project, List<GradlePluginInspection> plugins) {
        List<ExplainSignal> signals = new ArrayList<>();
        for (GradlePluginInspection plugin : plugins) {
            if (!plugin.applied()) {
                continue;
            }
            if (GradleSignalPatterns.isConventionPlugin(plugin)) {
                signals.add(ExplainSignals.GRADLE_PLUGIN_CONVENTION.signal(
                        project,
                        "Plugin `" + plugin.id() + "` looks like a convention plugin."));
            }
        }
        return signals;
    }

    private static List<ExplainSignal> unsupportedPluginSignals(
            String project,
            String content,
            List<GradlePluginInspection> plugins) {
        List<ExplainSignal> signals = new ArrayList<>();
        for (GradlePluginInspection plugin : plugins) {
            if (!plugin.applied()) {
                continue;
            }
            String id = plugin.id().toLowerCase();
            if (id.startsWith("com.android.") || id.equals("android")) {
                signals.add(ExplainSignals.GRADLE_ANDROID_UNSUPPORTED.signal(
                        project,
                        "Gradle plugin `" + plugin.id() + "` declares an Android project, which is outside the Zolt public beta."));
            }
            if (id.equals("org.jetbrains.kotlin.jvm") || id.equals("kotlin")) {
                signals.add(ExplainSignals.GRADLE_KOTLIN_MANUAL_MIGRATION.signal(
                        project,
                        "Gradle plugin `" + plugin.id()
                                + "` declares bounded Kotlin/JVM compilation that requires manual Zolt migration."));
            } else if (id.equals("org.jetbrains.kotlin")
                    || id.startsWith("org.jetbrains.kotlin.")
                    || id.equals("scala")) {
                signals.add(ExplainSignals.GRADLE_LANGUAGE_UNSUPPORTED.signal(
                        project,
                        "Gradle plugin `" + plugin.id()
                                + "` declares behavior outside Zolt's bounded Kotlin/JVM and supported"
                                + " Java/Groovy build surface."));
            }
            if (id.equals("org.graalvm.buildtools.native") || id.equals("io.micronaut.aot")) {
                signals.add(ExplainSignals.GRADLE_FRAMEWORK_NATIVE_UNSUPPORTED.signal(
                        project,
                        "Gradle plugin `" + plugin.id() + "` declares native/AOT behavior that Zolt does not execute as Gradle task behavior; migrate supported cases to typed Zolt framework settings."));
            }
        }
        if (containsAny(content, "bootBuildImage", "processAot", "processTestAot", "nativeCompile", "nativeTest", "quarkusDev")
                || content.contains("quarkus.native.enabled")) {
            signals.add(ExplainSignals.GRADLE_FRAMEWORK_NATIVE_UNSUPPORTED.signal(
                    project,
                    "Gradle build declares framework AOT, native, or dev-mode tasks that Zolt does not execute as Gradle task behavior; migrate supported cases to typed Zolt framework settings."));
        }
        return signals;
    }

    private static List<ExplainSignal> dynamicSignals(String project, String content) {
        List<ExplainSignal> signals = new ArrayList<>();
        if (GradleSignalPatterns.usesEnvironmentVariable(content)) {
            signals.add(ExplainSignals.GRADLE_ENVIRONMENT_VARIABLE_READ.signal(
                    project,
                    "Gradle build reads environment variables in executable build logic."));
        }
        if (containsAny(content, "dependencies.add(", "configurations.all", "resolutionStrategy", "afterEvaluate")) {
            signals.add(ExplainSignals.GRADLE_IMPERATIVE_DEPENDENCY_LOGIC.signal(
                    project,
                    "Gradle build uses imperative dependency or configuration mutation."));
        }
        if (Pattern.compile("\\b(subprojects|allprojects)\\s*\\{").matcher(content).find()) {
            signals.add(ExplainSignals.GRADLE_CROSS_PROJECT_BUILD_LOGIC.signal(
                    project,
                    "Gradle build uses cross-project script logic."));
        }
        if (Pattern.compile("\\btasks\\.(register|create)\\s*\\(").matcher(content).find()) {
            signals.add(ExplainSignals.GRADLE_CUSTOM_TASK_DETECTED.signal(
                    project,
                    "Gradle build declares custom tasks."));
        }
        if (GradleSignalPatterns.appliesScriptPlugin(content)) {
            signals.add(ExplainSignals.GRADLE_SCRIPT_PLUGIN_APPLY_FROM.signal(
                    project,
                    "Gradle build applies an external script plugin with apply from."));
        }
        if (GradleSignalPatterns.hasConditionalPluginApply(content)) {
            signals.add(ExplainSignals.GRADLE_PLUGIN_CONDITIONAL_APPLY.signal(
                    project,
                    "Gradle build conditionally applies a plugin in executable build logic."));
        }
        if (GradleSignalPatterns.hasStartParameterSelection(content)) {
            signals.add(ExplainSignals.GRADLE_START_PARAMETER_MUTATION.signal(
                    project,
                    "Gradle build reads or mutates startParameter task selection."));
        }
        if (GradleSignalPatterns.hasTaskMutation(content)) {
            signals.add(ExplainSignals.GRADLE_TASK_MUTATION_DETECTED.signal(
                    project,
                    "Gradle build mutates existing task behavior with configure blocks or enabled=false."));
        }
        return signals;
    }

    private static List<ExplainSignal> dependencySignals(
            String project,
            List<GradleDependencyInspection> dependencies) {
        List<ExplainSignal> signals = new ArrayList<>();
        for (GradleDependencyInspection dependency : dependencies) {
            String version = coordinateVersion(dependency.resolvedCoordinate());
            Optional<VersionPolicy.Violation> violation = unsupportedExternalVersion(version);
            if (violation.isPresent()) {
                signals.add(ExplainSignals.GRADLE_DEPENDENCY_DYNAMIC_VERSION.signal(
                        project,
                        "Dependency `"
                                + dependency.resolvedCoordinate()
                                + "` uses dynamic version `"
                                + version
                                + "` (version-policy rule: "
                                + violation.orElseThrow().rule()
                                + ")."));
            }
        }
        return signals;
    }

    private static List<ExplainSignal> pluginSignals(String project, List<GradlePluginInspection> plugins) {
        List<ExplainSignal> signals = new ArrayList<>();
        boolean enterpriseContext = plugins.stream()
                .filter(GradlePluginInspection::applied)
                .map(GradlePluginInspection::id)
                .anyMatch(GradleMigrationSignalDetector::enterprisePluginContext);
        if (!enterpriseContext) {
            return signals;
        }
        for (GradlePluginInspection plugin : plugins) {
            if (!plugin.applied()) {
                continue;
            }
            String mapping = enterprisePluginMapping(plugin.id());
            if (!mapping.isBlank()) {
                signals.add(ExplainSignals.GRADLE_ENTERPRISE_PLUGIN_MAPPED.signal(
                        project,
                        "Gradle plugin `" + plugin.id() + "` maps to " + mapping + "."));
            }
        }
        return signals;
    }

    private static String enterprisePluginMapping(String pluginId) {
        return switch (pluginId) {
            case "java", "java-library" -> "Zolt Java source, javac, classpath, and package primitives";
            case "war" -> "Zolt WAR and Spring Boot WAR package modes";
            case "org.springframework.boot" -> "Zolt Spring Boot platform, run, test, and executable archive support";
            case "io.spring.dependency-management" -> "Zolt [platforms] BOM imports and dependency policy";
            case "org.openapi.generator" -> "Zolt typed OpenAPI generated-source steps";
            case "jacoco" -> "Zolt coverage command";
            case "maven-publish" -> "planned Zolt publication metadata, dry-run, and publish commands";
            default -> "";
        };
    }

    private static boolean enterprisePluginContext(String pluginId) {
        return switch (pluginId) {
            case "war",
                    "org.springframework.boot",
                    "io.spring.dependency-management",
                    "org.openapi.generator",
                    "jacoco",
                    "maven-publish" -> true;
            default -> false;
        };
    }

    private static List<ExplainSignal> enterpriseSignals(String project, String content) {
        List<ExplainSignal> signals = new ArrayList<>();
        String repositoriesBlock = String.join("\n", new GradleBuildFileParser().repositoryBlocks(content));
        if (repositoriesBlock.contains("credentials")) {
            signals.add(ExplainSignals.GRADLE_REPOSITORY_CREDENTIALS.signal(
                    project,
                    "Gradle repository credentials are resolved inside the build script."));
        }
        if (repositoriesBlock.contains("mavenLocal()")) {
            signals.add(ExplainSignals.GRADLE_REPOSITORY_MAVEN_LOCAL.signal(
                    project,
                    "Gradle build can read Maven-local artifacts through mavenLocal()."));
        }
        if (containsAny(content, "resolutionStrategy", ".force ", " force '", " force \"", "exclude group:")) {
            signals.add(ExplainSignals.GRADLE_DEPENDENCY_POLICY_MUTATION.signal(
                    project,
                    "Gradle build mutates dependency policy through excludes, resolutionStrategy, or forced versions."));
        }
        if (containsAny(content, "GenerateTask", "generatorName", "inputSpec", "sourceSets") && content.contains("openapi")) {
            signals.add(ExplainSignals.GRADLE_OPENAPI_GENERATED_SOURCES.signal(
                    project,
                    "Gradle OpenAPI generator tasks feed generated Java sources into sourceSets."));
        }
        if (containsAny(content, "processResources", "ReplaceTokens", "filter(")) {
            signals.add(ExplainSignals.GRADLE_RESOURCE_FILTERING.signal(
                    project,
                    "Gradle processResources performs token/resource filtering."));
        }
        if (GradleSignalPatterns.hasTestRuntimeSettings(content)) {
            signals.add(ExplainSignals.GRADLE_TEST_RUNTIME_SETTINGS.signal(
                    project,
                    "Gradle test task declares runtime properties, environment, JVM args, or event logging."));
        }
        if (containsAny(content, "tasks.named('bootWar')", "tasks.named(\"bootWar\")", "bootWar {")
                && containsAny(content, "exclude(", "WEB-INF/lib")) {
            signals.add(ExplainSignals.GRADLE_PACKAGE_ARCHIVE_MUTATION.signal(
                    project,
                    "Gradle bootWar package content is changed with archive excludes."));
        }
        if (GradleSignalPatterns.hasPublicationConfiguration(content)) {
            signals.add(ExplainSignals.GRADLE_PUBLICATION_DETECTED.signal(
                    project,
                    "Gradle Maven Publish configuration selects artifacts and repositories."));
        }
        return signals;
    }

    private static boolean containsAny(String content, String... values) {
        for (String value : values) {
            if (content.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static String coordinateVersion(String coordinate) {
        int lastColon = coordinate.lastIndexOf(':');
        if (lastColon < 0 || lastColon == coordinate.length() - 1) {
            return "";
        }
        return coordinate.substring(lastColon + 1);
    }

    private static Optional<VersionPolicy.Violation> unsupportedExternalVersion(String version) {
        if (version == null || version.isBlank()) {
            return Optional.empty();
        }
        return VersionPolicy.violation(VersionPolicy.Context.EXTERNAL_DEPENDENCY, version);
    }
}
