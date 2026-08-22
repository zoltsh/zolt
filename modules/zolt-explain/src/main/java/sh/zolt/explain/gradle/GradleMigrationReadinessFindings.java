package sh.zolt.explain.gradle;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.MigrationReadinessCategory;
import sh.zolt.explain.MigrationReadinessFinding;
import sh.zolt.explain.MigrationReadinessFindings;

public final class GradleMigrationReadinessFindings {
    private GradleMigrationReadinessFindings() {
    }

    public static MigrationReadinessFinding map(ExplainSignal signal) {
        return switch (signal.id()) {
            case "gradle.build-src.detected" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "buildSrc executable build logic",
                    "explicit Zolt project model",
                    "",
                    signal.nextStep());
            case "gradle.exec-mappable" -> MigrationReadinessFindings.finding(
                    "generated-sources",
                    MigrationReadinessCategory.PLANNED,
                    signal,
                    "Gradle Exec/JavaExec single-command task",
                    "[generated.tools] exec step",
                    "",
                    signal.nextStep());
            case "gradle.exec-unmappable" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "Gradle scripted Exec/JavaExec task",
                    "[tasks.<id>] or CI",
                    "",
                    signal.nextStep());
            case "gradle.project.missing-build-file" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "settings include without readable build file",
                    "workspace members",
                    "",
                    signal.nextStep());
            case "gradle.project.build-file-name-unresolved" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.PLANNED,
                    signal,
                    "settings buildFileName mutation with candidate build file",
                    "workspace members",
                    "",
                    signal.nextStep());
            case "gradle.plugin.convention" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "convention plugin",
                    "explicit Zolt project model",
                    "",
                    signal.nextStep());
            case "gradle.script-plugin.apply-from" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "apply from script plugin",
                    "explicit Zolt project model",
                    "",
                    signal.nextStep());
            case "gradle.plugin.conditional-apply" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "conditional apply plugin",
                    "explicit Zolt project model",
                    "",
                    signal.nextStep());
            case "gradle.environment-variable.read" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "environment variable read in Gradle build logic",
                    "explicit Zolt project, runtime, or CI settings",
                    "",
                    signal.nextStep());
            case "gradle.settings.include-conditional" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "environment-conditional settings include",
                    "explicit workspace members",
                    "",
                    signal.nextStep());
            case "gradle.included-build.detected" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "includeBuild(...)",
                    "workspace member or external dependency",
                    "",
                    signal.nextStep());
            case "gradle.imperative-dependency-logic" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "imperative dependency or configuration mutation",
                    "[dependencies], classpath lanes, processors, and generated roots",
                    "",
                    signal.nextStep());
            case "gradle.dependency.dynamic-version" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "dynamic Gradle version",
                    "fixed versions, [versions], and [platforms]",
                    "",
                    signal.nextStep());
            case "gradle.dependency.unresolved-notation" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.UNKNOWN,
                    signal,
                    "unresolved Gradle dependency expression",
                    "explicit [dependencies]",
                    "",
                    signal.nextStep());
            case "gradle.cross-project-build-logic" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "allprojects/subprojects shared mutable build logic",
                    "explicit per-member workspace configuration",
                    "",
                    signal.nextStep());
            case "gradle.custom-task.detected" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.PLANNED,
                    signal,
                    "tasks.register(...) or task callbacks",
                    "typed Zolt command primitives",
                    "",
                    signal.nextStep());
            case "gradle.start-parameter.mutation" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "Gradle startParameter task selection",
                    "explicit Zolt command selection",
                    "",
                    signal.nextStep());
            case "gradle.task-mutation.detected" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.PLANNED,
                    signal,
                    "existing Gradle task mutation",
                    "typed Zolt build, test, package, or CI primitives",
                    "",
                    signal.nextStep());
            case "gradle.version-catalog.malformed" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "malformed gradle/libs.versions.toml",
                    "[versions] and [platforms]",
                    "",
                    signal.nextStep());
            case "gradle.version-catalog.bundle-unresolved" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "unresolved Gradle version-catalog bundle",
                    "[dependencies] with explicit library aliases",
                    "",
                    signal.nextStep());
            case "gradle.enterprise-plugin.mapped" -> mapPlugin(signal);
            case "gradle.repository.credentials" -> MigrationReadinessFindings.finding(
                    "repositories",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "credentials resolved from Gradle properties, env, or defaults",
                    "[repositories] credential identities",
                    "",
                    signal.nextStep());
            case "gradle.repository.maven-local" -> MigrationReadinessFindings.finding(
                    "repositories",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "mavenLocal() property switch",
                    "local repository overlays",
                    "",
                    signal.nextStep());
            case "gradle.dependency-policy.mutation" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "configurations.all, excludes, force, or resolutionStrategy",
                    "[dependencies.policy] and [dependencies.constraints]",
                    "",
                    signal.nextStep());
            case "gradle.openapi.generated-sources" -> MigrationReadinessFindings.finding(
                    "generated-sources",
                    MigrationReadinessCategory.SUPPORTED,
                    signal,
                    "OpenAPI GenerateTask wired into sourceSets",
                    "kind = \"openapi\" generated-source steps",
                    "",
                    signal.nextStep());
            case "gradle.resource-filtering" -> MigrationReadinessFindings.finding(
                    "resources",
                    MigrationReadinessCategory.SUPPORTED,
                    signal,
                    "processResources filter(ReplaceTokens, ...)",
                    "[resources.filter] and [resources.tokens]",
                    "",
                    signal.nextStep());
            case "gradle.test-runtime-settings" -> MigrationReadinessFindings.finding(
                    "tests",
                    MigrationReadinessCategory.SUPPORTED,
                    signal,
                    "test { systemProperty, environment, testLogging }",
                    "[test.runtime], reports, and event output",
                    "",
                    signal.nextStep());
            case "gradle.package.archive-mutation" -> MigrationReadinessFindings.finding(
                    "package",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "bootWar archive mutation",
                    "package placement policy",
                    "",
                    signal.nextStep());
            case "gradle.publication.detected" -> MigrationReadinessFindings.finding(
                    "publish",
                    MigrationReadinessCategory.PLANNED,
                    signal,
                    "publishing { publications; repositories }",
                    "[publish] and zolt publish --dry-run",
                    "",
                    signal.nextStep());
            case "gradle.language.unsupported" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.UNSUPPORTED,
                    signal,
                    "Gradle unsupported language plugin or main sources",
                    "normal Java application modules",
                    "",
                    signal.nextStep());
            case "gradle.android.unsupported" -> MigrationReadinessFindings.finding(
                    "package",
                    MigrationReadinessCategory.UNSUPPORTED,
                    signal,
                    "Gradle Android project",
                    "normal Java application package modes",
                    "",
                    signal.nextStep());
            case "gradle.framework-native.unsupported" -> MigrationReadinessFindings.finding(
                    "package",
                    MigrationReadinessCategory.UNSUPPORTED,
                    signal,
                    "Gradle framework-native or dev-mode behavior",
                    "typed Zolt framework settings",
                    "",
                    signal.nextStep());
            default -> MigrationReadinessFindings.generic(signal);
        };
    }

    private static MigrationReadinessFinding mapPlugin(ExplainSignal signal) {
        String message = signal.message();
        if (message.contains("jacoco")) {
            return MigrationReadinessFindings.finding(
                    "coverage",
                    MigrationReadinessCategory.SUPPORTED,
                    signal,
                    "id 'jacoco' and jacocoTestReport",
                    "zolt coverage",
                    "",
                    "Use explicit Zolt coverage commands instead of wiring coverage through test task finalizers.");
        }
        if (message.contains("maven-publish")) {
            return MigrationReadinessFindings.finding(
                    "publish",
                    MigrationReadinessCategory.PLANNED,
                    signal,
                    "id 'maven-publish'",
                    "[publish] and zolt publish --dry-run",
                    "",
                    "Map Maven Publish configuration to Zolt publication metadata, dry-run routing, and credential policy.");
        }
        if (message.contains("org.openapi.generator")) {
            return MigrationReadinessFindings.finding(
                    "generated-sources",
                    MigrationReadinessCategory.SUPPORTED,
                    signal,
                    "id 'org.openapi.generator'",
                    "kind = \"openapi\" generated-source steps",
                    "",
                    "Model OpenAPI generation as typed Zolt generated-source steps instead of Gradle GenerateTask closures.");
        }
        if (message.contains("org.springframework.boot") || message.contains("war")) {
            return MigrationReadinessFindings.finding(
                    "package",
                    MigrationReadinessCategory.SUPPORTED,
                    signal,
                    "Spring Boot or WAR Gradle plugin",
                    "spring-boot, war, and spring-boot-war package modes",
                    "",
                    "Declare the package mode and verify package placement with zolt package --plan.");
        }
        if (message.contains("io.spring.dependency-management")) {
            return MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.SUPPORTED,
                    signal,
                    "io.spring.dependency-management BOM imports",
                    "[platforms]",
                    "",
                    "Declare BOMs as Zolt platforms and verify the resolved lockfile.");
        }
        return MigrationReadinessFindings.finding(
                "dependencies",
                MigrationReadinessCategory.SUPPORTED,
                signal,
                "recognized Gradle plugin",
                "Zolt-owned Java build primitive",
                "",
                signal.nextStep());
    }
}
