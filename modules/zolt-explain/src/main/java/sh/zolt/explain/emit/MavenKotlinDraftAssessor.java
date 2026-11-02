package sh.zolt.explain.emit;

import sh.zolt.explain.maven.MavenDependencyInspection;
import sh.zolt.explain.maven.MavenJavaVersionProvenance;
import sh.zolt.explain.maven.MavenPluginInspection;
import sh.zolt.explain.maven.MavenProjectInspection;
import sh.zolt.manifest.SourceRootLanguage;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Adapts statically inspected Maven evidence into the shared Kotlin/JVM draft policy. */
final class MavenKotlinDraftAssessor {
    private static final String KOTLIN_GROUP = "org.jetbrains.kotlin";
    private static final String KOTLIN_PLUGIN = "kotlin-maven-plugin";
    private static final String STDLIB = "org.jetbrains.kotlin:kotlin-stdlib";
    private static final Set<String> SUPPORTED_COMPILER_PROPERTIES = Set.of(
            "maven.compiler.release",
            "maven.compiler.proc",
            "maven.compiler.testRelease",
            "project.build.sourceEncoding");

    private MavenKotlinDraftAssessor() {
    }

    static Result assess(
            MavenProjectInspection project,
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
        List<MavenPluginInspection> active = kotlinPlugins(project, false);
        MavenPluginInspection plugin = active.size() == 1 ? active.getFirst() : null;
        Optional<MavenKotlinDraftReason> mavenReason = mavenReason(project, active, plugin);
        boolean stdlibShape = supportedStdlibShape(project);
        if (mavenReason.isEmpty() && !stdlibShape) {
            mavenReason = Optional.of(MavenKotlinDraftReason.STDLIB_SHAPE);
        }
        boolean pluginShape = mavenReason.isEmpty()
                || mavenReason.orElseThrow() == MavenKotlinDraftReason.STDLIB_SHAPE;
        KotlinJvmDraftEligibility.Decision decision = KotlinJvmDraftEligibility.decide(
                new KotlinJvmDraftEligibility.Input(
                        pluginShape,
                        pluginVersion(plugin),
                        stdlibShape,
                        project.sourceRoots(),
                        project.testSourceRoots(),
                        dependencies));
        boolean mavenReasonSurfaced = decision instanceof KotlinJvmDraftEligibility.Decision.NeedsReview review
                && (review.reason() == KotlinJvmDraftEligibility.Reason.PLUGIN_SHAPE_NOT_PROVEN
                        || review.reason() == KotlinJvmDraftEligibility.Reason.STDLIB_SHAPE_NOT_PROVEN);
        return new Result(decision, mavenReasonSurfaced ? mavenReason : Optional.empty());
    }

    private static Optional<MavenKotlinDraftReason> mavenReason(
            MavenProjectInspection project,
            List<MavenPluginInspection> active,
            MavenPluginInspection plugin) {
        if (!project.parents().isEmpty()) {
            return Optional.of(MavenKotlinDraftReason.PARENT_MODEL);
        }
        if (!project.profiles().isEmpty()) {
            return Optional.of(MavenKotlinDraftReason.PROFILE_MODEL);
        }
        if (!"jar".equals(project.packaging())) {
            return Optional.of(MavenKotlinDraftReason.PACKAGING);
        }
        if (!project.artifactIdFixed()
                || !MavenKotlinDraftSafety.hasSafeKotlinModuleName(project)) {
            return Optional.of(MavenKotlinDraftReason.ARTIFACT_ID);
        }
        if (hasBackslashRoot(project.sourceRoots())
                || hasBackslashRoot(project.testSourceRoots())) {
            return Optional.of(MavenKotlinDraftReason.SOURCE_ROOT_SYNTAX);
        }
        if (active.size() != 1) {
            return Optional.of(MavenKotlinDraftReason.ACTIVE_PLUGIN_COUNT);
        }
        if (!kotlinPlugins(project, true).isEmpty()) {
            return Optional.of(MavenKotlinDraftReason.PLUGIN_MANAGEMENT);
        }
        if (!noOtherKotlinPlugins(project)) {
            return Optional.of(MavenKotlinDraftReason.OTHER_KOTLIN_PLUGIN);
        }
        if (hasActivePlugin(project, "org.codehaus.gmavenplus", "gmavenplus-plugin")) {
            return Optional.of(MavenKotlinDraftReason.GROOVY_PLUGIN);
        }
        if (hasUnsupportedLanguagePlugin(project)) {
            return Optional.of(MavenKotlinDraftReason.UNSUPPORTED_LANGUAGE_PLUGIN);
        }
        if (hasActivePlugin(project, "org.codehaus.mojo", "build-helper-maven-plugin")) {
            return Optional.of(MavenKotlinDraftReason.BUILD_HELPER_PLUGIN);
        }
        if (MavenKotlinDraftSafety.hasOtherLifecycleExtension(project)) {
            return Optional.of(MavenKotlinDraftReason.OTHER_LIFECYCLE_EXTENSION);
        }
        if (!project.annotationProcessors().isEmpty()) {
            return Optional.of(MavenKotlinDraftReason.ANNOTATION_PROCESSOR);
        }
        if (!"none".equalsIgnoreCase(project.mavenCompilerProc())) {
            return Optional.of(MavenKotlinDraftReason.JAVAC_PROCESSOR_DISCOVERY);
        }
        if (hasActivePlugin(project, "org.apache.maven.plugins", "maven-toolchains-plugin")) {
            return Optional.of(MavenKotlinDraftReason.MAVEN_TOOLCHAIN);
        }
        if (!MavenKotlinDraftSafety.hasSupportedCompilerPlugin(project)) {
            return Optional.of(MavenKotlinDraftReason.COMPILER_PLUGIN_CONTROLS);
        }
        if (project.compilerProperties().stream()
                .anyMatch(property -> !SUPPORTED_COMPILER_PROPERTIES.contains(property))) {
            return Optional.of(MavenKotlinDraftReason.COMPILER_PROPERTIES);
        }
        if (!MavenKotlinDraftSafety.hasUtf8SourceEncoding(project)) {
            return Optional.of(MavenKotlinDraftReason.SOURCE_ENCODING);
        }
        if (project.groovyTestSourcesPresent()) {
            return Optional.of(MavenKotlinDraftReason.GROOVY_TEST_SOURCES);
        }
        if (project.modularSources()) {
            return Optional.of(MavenKotlinDraftReason.MODULAR_SOURCES);
        }
        if (project.sourceLinksPresent()) {
            return Optional.of(MavenKotlinDraftReason.SOURCE_LINKS);
        }
        if (MavenKotlinDraftSafety.hasGenerationBehavior(project)) {
            return Optional.of(MavenKotlinDraftReason.GENERATED_SOURCES);
        }
        if (!automaticExtension(plugin)) {
            return Optional.of(MavenKotlinDraftReason.PLUGIN_SHAPE);
        }
        if (!"false".equalsIgnoreCase(plugin.kaptIncludeCompileClasspath())) {
            return Optional.of(MavenKotlinDraftReason.KAPT_COMPILE_CLASSPATH);
        }
        if (!compilerTargetMatches(project, plugin)) {
            return Optional.of(MavenKotlinDraftReason.COMPILER_TARGET);
        }
        if (project.explicitTestSourceDirectory()
                && hasKotlinRoot(project.testSourceRoots())) {
            return Optional.of(MavenKotlinDraftReason.EXPLICIT_TEST_ROOT);
        }
        return Optional.empty();
    }

    private static boolean automaticExtension(MavenPluginInspection plugin) {
        return plugin.goals().isEmpty()
                && !plugin.configurationPresent()
                && "true".equalsIgnoreCase(plugin.extensions())
                && !plugin.pluginDependenciesPresent()
                && plugin.disabledExecutions().isEmpty()
                && plugin.kotlinPluginProperties().isEmpty();
    }

    private static boolean compilerTargetMatches(
            MavenProjectInspection project,
            MavenPluginInspection plugin) {
        Optional<Integer> javaRelease = JavaVersionNotation.featureRelease(project.javaVersion());
        Optional<Integer> propertyRelease =
                JavaVersionNotation.featureRelease(project.mavenCompilerRelease());
        if (project.javaVersionProvenance() != MavenJavaVersionProvenance.RELEASE
                || (project.testJavaVersionProvenance() != MavenJavaVersionProvenance.UNKNOWN
                        && project.testJavaVersionProvenance() != MavenJavaVersionProvenance.RELEASE)
                || javaRelease.isEmpty()
                || !javaRelease.equals(propertyRelease)
                || !supportsAutomaticAlignment(pluginVersion(plugin))) {
            return false;
        }
        return project.testJavaVersion().isBlank()
                || javaRelease.equals(JavaVersionNotation.featureRelease(project.testJavaVersion()));
    }

    private static boolean supportsAutomaticAlignment(String version) {
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)*")) {
            return false;
        }
        String[] parts = version.split("\\.");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major == 2 && minor >= 4;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private static List<MavenPluginInspection> kotlinPlugins(
            MavenProjectInspection project,
            boolean pluginManagement) {
        return project.plugins().stream()
                .filter(plugin -> isKotlinPlugin(plugin.coordinate()))
                .filter(plugin -> plugin.pluginManagement() == pluginManagement)
                .toList();
    }

    private static boolean noOtherKotlinPlugins(MavenProjectInspection project) {
        return project.plugins().stream().noneMatch(plugin ->
                isKotlinGroup(plugin.coordinate()) && !isKotlinPlugin(plugin.coordinate()));
    }

    private static boolean hasActivePlugin(
            MavenProjectInspection project,
            String group,
            String artifact) {
        return project.plugins().stream()
                .filter(plugin -> !plugin.pluginManagement())
                .anyMatch(plugin -> coordinate(plugin.coordinate(), group, artifact));
    }

    private static boolean hasUnsupportedLanguagePlugin(MavenProjectInspection project) {
        return project.plugins().stream()
                .filter(plugin -> !plugin.pluginManagement())
                .anyMatch(MavenPluginInspection::unsupportedLanguagePlugin);
    }

    private static String pluginVersion(MavenPluginInspection plugin) {
        if (plugin == null) {
            return "";
        }
        String[] parts = plugin.coordinate().split(":", -1);
        return parts.length == 3 ? parts[2] : "";
    }

    private static boolean supportedStdlibShape(MavenProjectInspection project) {
        List<MavenDependencyInspection> kotlinStdlibs = project.dependencies().stream()
                .filter(dependency -> isKotlinStdlib(dependency.coordinate()))
                .toList();
        if (kotlinStdlibs.isEmpty()) {
            return true;
        }
        return kotlinStdlibs.stream().allMatch(dependency ->
                STDLIB.equals(coordinateOf(dependency.coordinate()))
                        && "jar".equals(dependency.type())
                        && !dependency.optional()
                        && !dependency.managed()
                        && !dependency.importedBom()
                        && dependency.classifier().isBlank());
    }

    private static boolean isKotlinPlugin(String coordinate) {
        return coordinate(coordinate, KOTLIN_GROUP, KOTLIN_PLUGIN);
    }

    private static boolean isKotlinGroup(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return parts.length >= 1 && KOTLIN_GROUP.equals(parts[0]);
    }

    private static boolean coordinate(String coordinate, String group, String artifact) {
        String[] parts = coordinate.split(":", -1);
        return parts.length >= 2 && group.equals(parts[0]) && artifact.equals(parts[1]);
    }

    private static boolean isKotlinStdlib(String coordinate) {
        String[] parts = coordinateOf(coordinate).split(":", -1);
        return parts.length == 2
                && KOTLIN_GROUP.equals(parts[0])
                && parts[1].startsWith("kotlin-stdlib");
    }

    private static String coordinateOf(String coordinate) {
        String[] parts = coordinate.split(":", -1);
        return parts.length >= 2 ? parts[0] + ":" + parts[1] : coordinate;
    }

    private static boolean hasKotlinRoot(List<String> roots) {
        return roots.stream().anyMatch(root ->
                SourceRootLanguage.unsupported(root).orElse(null) == SourceRootLanguage.KOTLIN);
    }

    private static boolean hasBackslashRoot(List<String> roots) {
        return roots.stream().anyMatch(root -> root.indexOf('\\') >= 0);
    }

    record Result(
            KotlinJvmDraftEligibility.Decision decision,
            Optional<MavenKotlinDraftReason> mavenReason) {
        Result {
            mavenReason = mavenReason == null ? Optional.empty() : mavenReason;
        }

        Optional<String> reviewNote() {
            if (!(decision instanceof KotlinJvmDraftEligibility.Decision.NeedsReview review)) {
                return Optional.empty();
            }
            String reason = mavenReason.map(MavenKotlinDraftReason::description)
                    .orElseGet(() -> sharedReason(review.reason()));
            return Optional.of(
                    "Kotlin/JVM roots were not emitted because " + reason
                            + ". Keep them as review data and author [toolchain.kotlin], source roots,"
                            + " and runtime dependencies only after preserving those Maven semantics.");
        }

        private static String sharedReason(KotlinJvmDraftEligibility.Reason reason) {
            return switch (reason) {
                case PLUGIN_SHAPE_NOT_PROVEN -> "the Kotlin Maven plugin shape was not proven";
                case PLUGIN_VERSION_NOT_FIXED -> "the Kotlin compiler version was not a fixed release";
                case STDLIB_SHAPE_NOT_PROVEN -> "the Maven kotlin-stdlib declaration was not a plain JAR";
                case SOURCE_LAYOUT_NOT_CONVENTIONAL -> "the Kotlin source layout was not conventional";
                case INCOMPATIBLE_LANGUAGE -> "the source set also contained an incompatible language";
                case STDLIB_MISSING -> "no direct kotlin-stdlib runtime was found";
                case STDLIB_NOT_FIXED -> "the kotlin-stdlib version was not fixed";
                case STDLIB_AMBIGUOUS -> "more than one kotlin-stdlib declaration could apply";
                case STDLIB_SCOPE_NOT_VISIBLE -> "the kotlin-stdlib scope was not compiler-visible";
                case STDLIB_VERSION_MISMATCH -> "the Kotlin compiler and kotlin-stdlib versions differed";
            };
        }
    }

}
