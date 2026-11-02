package sh.zolt.explain.emit;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.gradle.GradleDependencyInspection;
import sh.zolt.explain.gradle.GradleKotlinProjectEvidence;
import sh.zolt.explain.gradle.GradlePluginInspection;
import sh.zolt.explain.gradle.GradleProjectInspection;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Adapts conservative Gradle evidence into the shared Kotlin/JVM draft policy. */
final class GradleKotlinDraftAssessor {
    private static final String JVM_PLUGIN = "org.jetbrains.kotlin.jvm";
    private static final String KOTLIN_GROUP = "org.jetbrains.kotlin";
    private static final String STDLIB = "org.jetbrains.kotlin:kotlin-stdlib";
    private static final String STDLIB_PROPERTY = "kotlin.stdlib.default.dependency";
    private static final Set<String> SUPPORTED_PLUGINS = Set.of(
            JVM_PLUGIN,
            "application",
            "java",
            "java-library",
            "jacoco",
            "maven-publish");

    private GradleKotlinDraftAssessor() {
    }

    static Result assess(
            GradleProjectInspection project,
            DraftDependencies dependencies) {
        KotlinJvmDraftEligibility.Decision applicability = KotlinJvmDraftEligibility.decide(
                new KotlinJvmDraftEligibility.Input(
                        false,
                        "",
                        true,
                        project.sourceRoots(),
                        project.testSourceRoots(),
                        dependencies));
        if (applicability instanceof KotlinJvmDraftEligibility.Decision.NotApplicable) {
            return new Result(applicability, Optional.empty());
        }
        if (applicability instanceof KotlinJvmDraftEligibility.Decision.NeedsReview review
                && review.reason() != KotlinJvmDraftEligibility.Reason.PLUGIN_SHAPE_NOT_PROVEN) {
            return new Result(applicability, Optional.empty());
        }

        List<GradlePluginInspection> active = project.plugins().stream()
                .filter(GradlePluginInspection::applied)
                .toList();
        List<GradlePluginInspection> kotlinJvm = active.stream()
                .filter(plugin -> JVM_PLUGIN.equals(plugin.id()))
                .toList();
        GradlePluginInspection plugin = kotlinJvm.size() == 1 ? kotlinJvm.getFirst() : null;
        Optional<GradleKotlinDraftReason> reason = reason(project, active, kotlinJvm, plugin);
        if (reason.isPresent()) {
            return new Result(
                    new KotlinJvmDraftEligibility.Decision.NeedsReview(
                            KotlinJvmDraftEligibility.Reason.PLUGIN_SHAPE_NOT_PROVEN),
                    reason);
        }

        List<GradleDependencyInspection> stdlibs = stdlibFamily(project.dependencies());
        boolean stdlibShape = stdlibs.stream().allMatch(GradleKotlinDraftAssessor::plainStdlib);
        if (!stdlibShape) {
            return review(GradleKotlinDraftReason.STDLIB_SHAPE);
        }
        if (stdlibs.isEmpty()) {
            String defaultDependency = project.kotlinEvidence().stdlibDefaultDependency();
            if (!defaultDependency.isBlank()
                    && !"true".equalsIgnoreCase(defaultDependency)) {
                return review(GradleKotlinDraftReason.STDLIB_DEFAULT);
            }
            dependencies.fixed(
                    DependencyLane.IMPLEMENTATION,
                    STDLIB,
                    plugin.version());
        }

        KotlinJvmDraftEligibility.Decision decision = KotlinJvmDraftEligibility.decide(
                new KotlinJvmDraftEligibility.Input(
                        true,
                        plugin.version(),
                        true,
                        project.sourceRoots(),
                        project.testSourceRoots(),
                        dependencies));
        return new Result(decision, Optional.empty());
    }

    private static Optional<GradleKotlinDraftReason> reason(
            GradleProjectInspection project,
            List<GradlePluginInspection> active,
            List<GradlePluginInspection> kotlinJvm,
            GradlePluginInspection plugin) {
        GradleKotlinProjectEvidence evidence = project.kotlinEvidence();
        if (!Path.of(".").equals(project.path())) {
            return reason(GradleKotlinDraftReason.ROOT_PROJECT);
        }
        if (!evidence.projectNameProven() || !safeModuleName(project.name())) {
            return reason(GradleKotlinDraftReason.PROJECT_NAME);
        }
        if (!evidence.settingsShapeProven()) {
            return reason(GradleKotlinDraftReason.SETTINGS_SHAPE);
        }
        if (!evidence.pluginBlockShapeProven()) {
            return reason(GradleKotlinDraftReason.PLUGIN_BLOCK);
        }
        if (kotlinJvm.size() != 1) {
            return reason(GradleKotlinDraftReason.ACTIVE_PLUGIN_COUNT);
        }
        if (active.stream().anyMatch(candidate ->
                candidate.id().startsWith(KOTLIN_GROUP + ".")
                        && !JVM_PLUGIN.equals(candidate.id()))) {
            return reason(GradleKotlinDraftReason.OTHER_KOTLIN_PLUGIN);
        }
        if (active.stream().anyMatch(candidate -> !SUPPORTED_PLUGINS.contains(candidate.id()))) {
            return reason(GradleKotlinDraftReason.OTHER_APPLIED_PLUGIN);
        }
        if (!qualifiedPluginVersion(plugin.version())) {
            return reason(GradleKotlinDraftReason.PLUGIN_VERSION);
        }
        if (!alignedJavaToolchain(project)) {
            return reason(GradleKotlinDraftReason.JAVA_TOOLCHAIN);
        }
        if (evidence.javaCompatibilityConfigured()) {
            return reason(GradleKotlinDraftReason.JAVA_COMPATIBILITY);
        }
        if (evidence.kotlinExtensionConfigured()) {
            return reason(GradleKotlinDraftReason.KOTLIN_EXTENSION);
        }
        if (evidence.kotlinCompilerControlsConfigured()) {
            return reason(GradleKotlinDraftReason.KOTLIN_COMPILER_CONTROLS);
        }
        if (unsupportedKotlinProperties(evidence)) {
            return reason(GradleKotlinDraftReason.KOTLIN_PROPERTIES);
        }
        if (evidence.sourceSetsConfigured()) {
            return reason(GradleKotlinDraftReason.SOURCE_SETS);
        }
        if (evidence.annotationProcessingConfigured()) {
            return reason(GradleKotlinDraftReason.ANNOTATION_PROCESSING);
        }
        if (evidence.buildSrcPresent()) {
            return reason(GradleKotlinDraftReason.BUILD_SRC);
        }
        if (evidence.dependencyResolutionConfigured()) {
            return reason(GradleKotlinDraftReason.DEPENDENCY_RESOLUTION);
        }
        if (!evidence.declarativeBuildShapeProven()) {
            return reason(GradleKotlinDraftReason.BUILD_SHAPE);
        }
        if (!project.groovyTestSourceRoots().isEmpty()) {
            return reason(GradleKotlinDraftReason.GROOVY_SOURCES);
        }
        if (evidence.sourceTree().modularSources()) {
            return reason(GradleKotlinDraftReason.MODULAR_SOURCES);
        }
        if (evidence.sourceTree().sourceLinksPresent()) {
            return reason(GradleKotlinDraftReason.SOURCE_LINKS);
        }
        if (evidence.sourceTree().mainJavaSourcesPresent()
                || evidence.sourceTree().testJavaSourcesPresent()) {
            return reason(GradleKotlinDraftReason.JAVA_SOURCES);
        }
        if (project.dependencies().stream().anyMatch(GradleDependencyInspection::isPlatform)) {
            return reason(GradleKotlinDraftReason.PLATFORM_DEPENDENCY);
        }
        if (!evidence.dependencyDeclarationsProven()
                || project.dependencies().stream().anyMatch(GradleKotlinDraftAssessor::unresolvedDependency)) {
            return reason(GradleKotlinDraftReason.DEPENDENCY_DECLARATIONS);
        }
        return Optional.empty();
    }

    private static boolean unresolvedDependency(GradleDependencyInspection dependency) {
        String coordinate = dependency.resolvedCoordinate();
        if (coordinate == null) {
            return true;
        }
        String[] parts = coordinate.split(":", -1);
        return parts.length != 3
                || parts[0].isBlank()
                || parts[1].isBlank()
                || parts[2].isBlank();
    }

    private static boolean alignedJavaToolchain(GradleProjectInspection project) {
        GradleKotlinProjectEvidence evidence = project.kotlinEvidence();
        Optional<Integer> projectRelease = JavaVersionNotation.featureRelease(project.javaVersion());
        Optional<Integer> toolchainRelease = JavaVersionNotation.featureRelease(
                evidence.javaToolchainVersion());
        return evidence.javaToolchainShapeProven()
                && projectRelease.isPresent()
                && projectRelease.equals(toolchainRelease)
                && projectRelease.orElseThrow() >= 8
                && projectRelease.orElseThrow() <= 21;
    }

    private static boolean unsupportedKotlinProperties(GradleKotlinProjectEvidence evidence) {
        if (evidence.kotlinProperties().stream().anyMatch(name -> !STDLIB_PROPERTY.equals(name))) {
            return true;
        }
        String value = evidence.stdlibDefaultDependency();
        return !value.isBlank()
                && !"true".equalsIgnoreCase(value)
                && !"false".equalsIgnoreCase(value);
    }

    private static boolean qualifiedPluginVersion(String version) {
        return version != null
                && version.matches("2\\.(?:2|3)\\.[0-9]+(?:\\.[0-9]+)*");
    }

    private static boolean safeModuleName(String name) {
        try {
            ProjectPaths.filenameComponent("Gradle Kotlin module name", name);
            return true;
        } catch (ProjectPathException exception) {
            return false;
        }
    }

    private static List<GradleDependencyInspection> stdlibFamily(
            List<GradleDependencyInspection> dependencies) {
        return dependencies.stream()
                .filter(dependency -> {
                    String[] coordinate = coordinate(dependency);
                    return coordinate.length >= 2
                            && KOTLIN_GROUP.equals(coordinate[0])
                            && coordinate[1].startsWith("kotlin-stdlib");
                })
                .toList();
    }

    private static boolean plainStdlib(GradleDependencyInspection dependency) {
        String[] coordinate = coordinate(dependency);
        return !dependency.isPlatform()
                && coordinate.length >= 2
                && STDLIB.equals(coordinate[0] + ":" + coordinate[1]);
    }

    private static String[] coordinate(GradleDependencyInspection dependency) {
        String resolved = dependency.resolvedCoordinate();
        return resolved == null ? new String[0] : resolved.split(":", -1);
    }

    private static Optional<GradleKotlinDraftReason> reason(
            GradleKotlinDraftReason reason) {
        return Optional.of(reason);
    }

    private static Result review(GradleKotlinDraftReason reason) {
        return new Result(
                new KotlinJvmDraftEligibility.Decision.NeedsReview(
                        KotlinJvmDraftEligibility.Reason.STDLIB_SHAPE_NOT_PROVEN),
                Optional.of(reason));
    }

    record Result(
            KotlinJvmDraftEligibility.Decision decision,
            Optional<GradleKotlinDraftReason> gradleReason) {
        Result {
            gradleReason = gradleReason == null ? Optional.empty() : gradleReason;
        }

        Optional<String> reviewNote() {
            if (!(decision instanceof KotlinJvmDraftEligibility.Decision.NeedsReview review)) {
                return Optional.empty();
            }
            String reason = gradleReason.map(GradleKotlinDraftReason::description)
                    .orElseGet(() -> sharedReason(review.reason()));
            return Optional.of(
                    "Kotlin/JVM roots were not emitted because " + reason
                            + ". Keep them as review data and author [toolchain.kotlin], source roots,"
                            + " runtime dependencies, and compiler module names only after preserving"
                            + " those Gradle semantics.");
        }

        private static String sharedReason(KotlinJvmDraftEligibility.Reason reason) {
            return switch (reason) {
                case PLUGIN_SHAPE_NOT_PROVEN -> "the Kotlin Gradle plugin shape was not proven";
                case PLUGIN_VERSION_NOT_FIXED -> "the Kotlin compiler version was not a fixed release";
                case STDLIB_SHAPE_NOT_PROVEN -> "the Gradle kotlin-stdlib declaration was not proven";
                case SOURCE_LAYOUT_NOT_CONVENTIONAL -> "the Kotlin source layout was not conventional";
                case INCOMPATIBLE_LANGUAGE -> "the source set also contained an incompatible language";
                case STDLIB_MISSING -> "no Kotlin standard-library runtime was found";
                case STDLIB_NOT_FIXED -> "the kotlin-stdlib version was not fixed";
                case STDLIB_AMBIGUOUS -> "more than one kotlin-stdlib declaration could apply";
                case STDLIB_SCOPE_NOT_VISIBLE -> "the kotlin-stdlib scope was not compiler-visible";
                case STDLIB_VERSION_MISMATCH -> "the Kotlin compiler and kotlin-stdlib versions differed";
            };
        }
    }
}
