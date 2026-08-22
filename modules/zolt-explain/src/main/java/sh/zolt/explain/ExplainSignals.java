package sh.zolt.explain;

import java.util.List;

public final class ExplainSignals {
    public static final ExplainSignalDefinition MAVEN_MODULE_MISSING_POM = new ExplainSignalDefinition(
            "maven.module.missing-pom",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.BUILDABILITY,
            "Fix the module path or exclude it from migration scope before generating Zolt metadata.");
    public static final ExplainSignalDefinition MAVEN_PACKAGING_UNSUPPORTED = new ExplainSignalDefinition(
            "maven.packaging.unsupported",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Map this packaging mode to Zolt package settings before migrating.");
    public static final ExplainSignalDefinition MAVEN_DEPENDENCY_DYNAMIC_VERSION = new ExplainSignalDefinition(
            "maven.dependency.dynamic-version",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.NON_DETERMINISM,
            "Replace Maven ranges or SNAPSHOTs with fixed versions before migrating.");
    public static final ExplainSignalDefinition MAVEN_DEPENDENCY_UNRESOLVED_VERSION = new ExplainSignalDefinition(
            "maven.dependency.unresolved-version",
            ExplainSignal.Severity.UNKNOWN,
            ExplainSignal.Category.BUILDABILITY,
            "Resolve the Maven property or set a fixed dependency version before relying on the migration draft.");
    public static final ExplainSignalDefinition MAVEN_DEPENDENCY_MISSING_VERSION = new ExplainSignalDefinition(
            "maven.dependency.missing-version",
            ExplainSignal.Severity.UNKNOWN,
            ExplainSignal.Category.BUILDABILITY,
            "Resolve inherited dependency management or add an explicit fixed dependency version before migrating.");
    public static final ExplainSignalDefinition MAVEN_PLUGIN_LIFECYCLE_BINDING = new ExplainSignalDefinition(
            "maven.plugin.lifecycle-binding",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Replace plugin-generated behavior with explicit Zolt configuration or keep it in manual-review scope.");
    public static final ExplainSignalDefinition MAVEN_PLUGIN_STATIC_SIGNAL = new ExplainSignalDefinition(
            "maven.plugin.static-signal",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Review whether this plugin changes compilation, resources, tests, packaging, or generated outputs.");
    public static final ExplainSignalDefinition MAVEN_PLUGIN_EXEC_MAPPABLE = new ExplainSignalDefinition(
            "maven.plugin.exec-mappable",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Review the drafted exec step: declare its real input closure and owned output directory"
                    + " (Zolt cannot infer them statically) and confirm the pinned tool identity before building.");
    public static final ExplainSignalDefinition MAVEN_PLUGIN_EXEC_NONDETERMINISTIC = new ExplainSignalDefinition(
            "maven.plugin.exec-nondeterministic",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.NON_DETERMINISM,
            "Generate from committed DDL/schema so the step is reproducible, or set cache = \"none\" to accept"
                    + " always-run, non-hermetic generation that is excluded from offline builds.");
    public static final ExplainSignalDefinition MAVEN_PLUGIN_EXEC_UNMAPPABLE = new ExplainSignalDefinition(
            "maven.plugin.exec-unmappable",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Exec steps run one pinned tool over declared inputs with no shell; keep shell scripts and"
                    + " multi-step build logic in [tasks.<id>] or CI.");
    public static final ExplainSignalDefinition MAVEN_PROFILE_DETECTED = new ExplainSignalDefinition(
            "maven.profile.detected",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.NON_DETERMINISM,
            "Move profile-specific behavior into explicit Zolt configuration or keep it in manual-review scope.");
    public static final ExplainSignalDefinition MAVEN_PROFILE_MODULES_DETECTED = new ExplainSignalDefinition(
            "maven.profile.modules.detected",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.BUILDABILITY,
            "Review profile-declared modules and add active members explicitly before relying on workspace emit.");
    public static final ExplainSignalDefinition MAVEN_LANGUAGE_UNSUPPORTED = new ExplainSignalDefinition(
            "maven.language.unsupported",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Keep Kotlin, Scala, and Android modules outside the public beta or migrate a plain Java module first.");
    public static final ExplainSignalDefinition MAVEN_FRAMEWORK_NATIVE_UNSUPPORTED = new ExplainSignalDefinition(
            "maven.framework-native.unsupported",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Do not execute Maven framework-native plugins; migrate supported cases to typed Zolt framework settings such as `[framework.spring-boot] native = true`.");
    public static final ExplainSignalDefinition MAVEN_REACTOR_DETECTED = new ExplainSignalDefinition(
            "maven.reactor.detected",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Run zolt explain --emit-toml to generate the Zolt workspace, then verify each member draft.");
    public static final ExplainSignalDefinition MAVEN_ANNOTATION_PROCESSOR_PATH = new ExplainSignalDefinition(
            "maven.annotation-processor.path",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Map compiler annotation processor paths to [dependencies.processor] and verify generated code.");
    public static final ExplainSignalDefinition MAVEN_PARENT_SNAPSHOT = new ExplainSignalDefinition(
            "maven.parent.snapshot",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.NON_DETERMINISM,
            "Replace SNAPSHOT parents with fixed releases or vendor the inherited settings explicitly before migrating.");
    public static final ExplainSignalDefinition MAVEN_PARENT_UNRESOLVED = new ExplainSignalDefinition(
            "maven.parent.unresolved",
            ExplainSignal.Severity.UNKNOWN,
            ExplainSignal.Category.BUILDABILITY,
            "Re-run zolt explain with --resolve-external-parents to fetch the external parent POM chain, or vendor the inherited Maven settings before relying on the draft.");
    public static final ExplainSignalDefinition MAVEN_EXTERNAL_PARENT_RESOLVED = new ExplainSignalDefinition(
            "maven.external-parent.resolved",
            ExplainSignal.Severity.OK,
            ExplainSignal.Category.BUILDABILITY,
            "Recovered external parent/BOM metadata was fetched over the network; review the listed coordinates and pin them if the draft must stay reproducible offline.");
    public static final ExplainSignalDefinition MAVEN_BOM_DETECTED = new ExplainSignalDefinition(
            "maven.bom.detected",
            ExplainSignal.Severity.OK,
            ExplainSignal.Category.BUILDABILITY,
            "This is a dependencyManagement BOM; `zolt explain --emit-toml` drafts a [bom] member (import-scope BOMs to [bom.imports], plain pins to [bom.versions]).");
    public static final ExplainSignalDefinition MAVEN_JAVA_VERSION_UNKNOWN = new ExplainSignalDefinition(
            "maven.java-version.unknown",
            ExplainSignal.Severity.UNKNOWN,
            ExplainSignal.Category.BUILDABILITY,
            "Declare the Maven compiler release/source/target or set [project].java before migrating.");
    public static final ExplainSignalDefinition MAVEN_JPMS_MODULE_INFO_DETECTED = new ExplainSignalDefinition(
            "maven.jpms.module-info-detected",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Raise the Java version to 9+ (or add multi-release handling) so the module-info.java under this source root can compile.");
    public static final ExplainSignalDefinition MAVEN_TEST_JAVA_VERSION_DIVERGENT = new ExplainSignalDefinition(
            "maven.test-java-version.divergent",
            ExplainSignal.Severity.UNKNOWN,
            ExplainSignal.Category.BUILDABILITY,
            "Align Maven main/test Java levels or review the test compilation model before migrating.");
    public static final ExplainSignalDefinition MAVEN_COMPILER_PLATFORM_API_HOST_CANDIDATE =
            new ExplainSignalDefinition(
                    "maven.compiler.platform-api-host-candidate",
                    ExplainSignal.Severity.WARN,
                    ExplainSignal.Category.NON_DETERMINISM,
                    "This POM used source/target below the build JDK, so Maven compiled against the host"
                            + " JDK API. Zolt defaults to reproducible --release; only uncomment [compiler]"
                            + " jdkApi = \"host\" if a genuine post-target platform API fails the strict"
                            + " build, and note that host mode forfeits cross-JDK reproducibility.");
    public static final ExplainSignalDefinition MAVEN_REPOSITORY_DECLARED = new ExplainSignalDefinition(
            "maven.repository.declared",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.CACHEABILITY,
            "Map declared Maven repositories to explicit Zolt repository configuration and cache policy.");
    public static final ExplainSignalDefinition MAVEN_REPOSITORY_SNAPSHOTS_ENABLED = new ExplainSignalDefinition(
            "maven.repository.snapshots-enabled",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.NON_DETERMINISM,
            "Disable SNAPSHOT repositories or pin all consumed artifacts to release repositories before migrating.");

    public static final ExplainSignalDefinition GRADLE_BUILD_SRC_DETECTED = new ExplainSignalDefinition(
            "gradle.build-src.detected",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Review buildSrc logic and replace generated behavior with explicit Zolt configuration before migrating.");
    public static final ExplainSignalDefinition GRADLE_PROJECT_MISSING_BUILD_FILE = new ExplainSignalDefinition(
            "gradle.project.missing-build-file",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.BUILDABILITY,
            "Fix the include path or exclude it from migration scope before generating Zolt metadata.");
    public static final ExplainSignalDefinition GRADLE_PROJECT_BUILD_FILE_NAME_UNRESOLVED = new ExplainSignalDefinition(
            "gradle.project.build-file-name-unresolved",
            ExplainSignal.Severity.UNKNOWN,
            ExplainSignal.Category.BUILDABILITY,
            "Review the settings buildFileName logic and choose the included project's build file before generating Zolt metadata.");
    public static final ExplainSignalDefinition GRADLE_PLUGIN_CONVENTION = new ExplainSignalDefinition(
            "gradle.plugin.convention",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Review the plugin implementation and model its effects explicitly in Zolt before migrating.");
    public static final ExplainSignalDefinition GRADLE_SCRIPT_PLUGIN_APPLY_FROM = new ExplainSignalDefinition(
            "gradle.script-plugin.apply-from",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Review the applied Gradle script plugin and model its effects explicitly in Zolt before migrating.");
    public static final ExplainSignalDefinition GRADLE_PLUGIN_CONDITIONAL_APPLY = new ExplainSignalDefinition(
            "gradle.plugin.conditional-apply",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Review the conditional plugin path and model the plugin effects explicitly in Zolt before migrating.");
    public static final ExplainSignalDefinition GRADLE_ENVIRONMENT_VARIABLE_READ = new ExplainSignalDefinition(
            "gradle.environment-variable.read",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.NON_DETERMINISM,
            "Move environment-selected build behavior into explicit Zolt configuration or local/CI command settings.");
    public static final ExplainSignalDefinition GRADLE_SETTINGS_INCLUDE_CONDITIONAL = new ExplainSignalDefinition(
            "gradle.settings.include-conditional",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.NON_DETERMINISM,
            "Choose the workspace members explicitly before generating Zolt metadata.");
    public static final ExplainSignalDefinition GRADLE_INCLUDED_BUILD_DETECTED = new ExplainSignalDefinition(
            "gradle.included-build.detected",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Review included build logic and migrate it as an explicit Zolt workspace or external dependency.");
    public static final ExplainSignalDefinition GRADLE_IMPERATIVE_DEPENDENCY_LOGIC = new ExplainSignalDefinition(
            "gradle.imperative-dependency-logic",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Replace imperative Gradle logic with explicit Zolt dependencies, platforms, processors, and source roots.");
    public static final ExplainSignalDefinition GRADLE_DEPENDENCY_DYNAMIC_VERSION = new ExplainSignalDefinition(
            "gradle.dependency.dynamic-version",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.NON_DETERMINISM,
            "Replace Gradle dynamic versions with fixed versions or explicit platforms before migrating.");
    public static final ExplainSignalDefinition GRADLE_DEPENDENCY_UNRESOLVED_NOTATION = new ExplainSignalDefinition(
            "gradle.dependency.unresolved-notation",
            ExplainSignal.Severity.UNKNOWN,
            ExplainSignal.Category.BUILDABILITY,
            "Review the Gradle dependency expression and add an explicit Zolt dependency if it applies.");
    public static final ExplainSignalDefinition GRADLE_CROSS_PROJECT_BUILD_LOGIC = new ExplainSignalDefinition(
            "gradle.cross-project-build-logic",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Move shared behavior into explicit per-member Zolt configuration or a documented workspace convention.");
    public static final ExplainSignalDefinition GRADLE_CUSTOM_TASK_DETECTED = new ExplainSignalDefinition(
            "gradle.custom-task.detected",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Review whether custom tasks generate sources, resources, tests, package outputs, or runtime assets.");
    public static final ExplainSignalDefinition GRADLE_START_PARAMETER_MUTATION = new ExplainSignalDefinition(
            "gradle.start-parameter.mutation",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.NON_DETERMINISM,
            "Replace Gradle startParameter task selection with explicit Zolt command selection before relying on migration readiness.");
    public static final ExplainSignalDefinition GRADLE_TASK_MUTATION_DETECTED = new ExplainSignalDefinition(
            "gradle.task-mutation.detected",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Review mutated Gradle task behavior and model required compile, test, package, or CI behavior explicitly in Zolt.");
    public static final ExplainSignalDefinition GRADLE_VERSION_CATALOG_MALFORMED = new ExplainSignalDefinition(
            "gradle.version-catalog.malformed",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Fix gradle/libs.versions.toml before relying on version catalog migration hints.");
    public static final ExplainSignalDefinition GRADLE_VERSION_CATALOG_BUNDLE_UNRESOLVED = new ExplainSignalDefinition(
            "gradle.version-catalog.bundle-unresolved",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Define the missing bundle member libraries in gradle/libs.versions.toml and add them to the draft by hand.");
    public static final ExplainSignalDefinition GRADLE_ENTERPRISE_PLUGIN_MAPPED = new ExplainSignalDefinition(
            "gradle.enterprise-plugin.mapped",
            ExplainSignal.Severity.OK,
            ExplainSignal.Category.BUILDABILITY,
            "Map the plugin to the named Zolt primitive and verify the generated zolt.toml with zolt explain.");
    public static final ExplainSignalDefinition GRADLE_REPOSITORY_CREDENTIALS = new ExplainSignalDefinition(
            "gradle.repository.credentials",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.NON_DETERMINISM,
            "Move credential lookup to Zolt repository credential identities or local/CI secret config.");
    public static final ExplainSignalDefinition GRADLE_REPOSITORY_MAVEN_LOCAL = new ExplainSignalDefinition(
            "gradle.repository.maven-local",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.NON_DETERMINISM,
            "Use explicit Zolt repository overlays for local development and reject them in CI with --no-local-overlays.");
    public static final ExplainSignalDefinition GRADLE_DEPENDENCY_POLICY_MUTATION = new ExplainSignalDefinition(
            "gradle.dependency-policy.mutation",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Replace global Gradle excludes and forced versions with explicit Zolt dependency policy constraints.");
    public static final ExplainSignalDefinition GRADLE_OPENAPI_GENERATED_SOURCES = new ExplainSignalDefinition(
            "gradle.openapi.generated-sources",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Model OpenAPI generation as typed Zolt generated-source steps instead of Gradle GenerateTask closures.");
    public static final ExplainSignalDefinition GRADLE_RESOURCE_FILTERING = new ExplainSignalDefinition(
            "gradle.resource-filtering",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.CACHEABILITY,
            "Move resource token replacement to Zolt resource filtering with explicit tokens and include globs.");
    public static final ExplainSignalDefinition GRADLE_TEST_RUNTIME_SETTINGS = new ExplainSignalDefinition(
            "gradle.test-runtime-settings",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Move test system properties, environment, JVM args, and reporting to Zolt test runtime settings.");
    public static final ExplainSignalDefinition GRADLE_PACKAGE_ARCHIVE_MUTATION = new ExplainSignalDefinition(
            "gradle.package.archive-mutation",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Replace package archive mutation with Zolt dependency scopes and package placement diagnostics.");
    public static final ExplainSignalDefinition GRADLE_PUBLICATION_DETECTED = new ExplainSignalDefinition(
            "gradle.publication.detected",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Map Maven Publish configuration to Zolt publication metadata, dry-run routing, and credential policy.");
    public static final ExplainSignalDefinition GRADLE_LANGUAGE_UNSUPPORTED = new ExplainSignalDefinition(
            "gradle.language.unsupported",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Keep Kotlin, Scala, and Groovy main-source modules outside the public beta or migrate a plain Java module first.");
    public static final ExplainSignalDefinition GRADLE_ANDROID_UNSUPPORTED = new ExplainSignalDefinition(
            "gradle.android.unsupported",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Android is outside the public beta; migrate normal Java application modules first.");
    public static final ExplainSignalDefinition GRADLE_FRAMEWORK_NATIVE_UNSUPPORTED = new ExplainSignalDefinition(
            "gradle.framework-native.unsupported",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Do not execute Gradle framework-native or dev-mode tasks; migrate supported cases to typed Zolt framework settings such as `[framework.spring-boot] native = true`.");
    public static final ExplainSignalDefinition GRADLE_EXEC_MAPPABLE = new ExplainSignalDefinition(
            "gradle.exec-mappable",
            ExplainSignal.Severity.WARN,
            ExplainSignal.Category.BUILDABILITY,
            "Model this single-command Exec/JavaExec task as a Zolt exec step under [generated.tools];"
                    + " declare its tool, inputs, and output by hand (Zolt reads the task shape, not its full command).");
    public static final ExplainSignalDefinition GRADLE_EXEC_UNMAPPABLE = new ExplainSignalDefinition(
            "gradle.exec-unmappable",
            ExplainSignal.Severity.BLOCK,
            ExplainSignal.Category.MIGRATION_BLOCKER,
            "Exec steps run one pinned tool over declared inputs with no shell or task actions;"
                    + " keep this scripted Exec/JavaExec task in [tasks.<id>] or CI.");
    public static final ExplainSignalDefinition GRADLE_BOM_DETECTED = new ExplainSignalDefinition(
            "gradle.bom.detected",
            ExplainSignal.Severity.OK,
            ExplainSignal.Category.BUILDABILITY,
            "This is a java-platform BOM; `zolt explain --emit-toml` drafts a [bom] member (platform() imports to [bom.imports], constraints to [bom.versions]).");

    private ExplainSignals() {
    }

    public static List<ExplainSignalDefinition> definitions() {
        return ExplainSignalRegistry.definitions();
    }

    public static List<ExplainSignal> sorted(List<ExplainSignal> signals) {
        return ExplainSignalRegistry.sorted(signals);
    }
}
