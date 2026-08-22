package sh.zolt.explain.maven;

import sh.zolt.explain.ExplainSignal;
import sh.zolt.explain.MigrationReadinessCategory;
import sh.zolt.explain.MigrationReadinessFinding;
import sh.zolt.explain.MigrationReadinessFindings;
import java.util.Locale;

public final class MavenMigrationReadinessFindings {
    private MavenMigrationReadinessFindings() {
    }

    public static MigrationReadinessFinding map(ExplainSignal signal) {
        return switch (signal.id()) {
            case "maven.module.missing-pom" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "module listed in <modules> without a readable pom.xml",
                    "workspace members",
                    "",
                    signal.nextStep());
            case "maven.packaging.unsupported" -> MigrationReadinessFindings.finding(
                    "package",
                    MigrationReadinessCategory.UNSUPPORTED,
                    signal,
                    "<packaging> value without a Zolt package mode",
                    "[package].mode",
                    "",
                    "Replace this packaging with a supported Zolt package mode or keep it outside MVP migration scope.");
            case "maven.dependency.dynamic-version" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "SNAPSHOT or Maven version range",
                    "fixed versions and [platforms]",
                    "",
                    signal.nextStep());
            case "maven.dependency.unresolved-version" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.UNKNOWN,
                    signal,
                    "unresolved Maven dependency version property",
                    "fixed [dependencies] versions",
                    "",
                    signal.nextStep());
            case "maven.dependency.missing-version" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.UNKNOWN,
                    signal,
                    "missing Maven dependency version from unresolved parent metadata",
                    "fixed [dependencies] versions or imported platforms",
                    "",
                    signal.nextStep());
            case "maven.plugin.lifecycle-binding" -> mapPlugin(
                    signal,
                    MigrationReadinessCategory.BLOCKED,
                    "Maven plugin bound to lifecycle phase");
            case "maven.plugin.static-signal" -> mapPlugin(
                    signal,
                    MigrationReadinessCategory.PLANNED,
                    "Maven plugin declared for static migration review");
            case "maven.plugin.exec-mappable" -> MigrationReadinessFindings.finding(
                    "generated-sources",
                    MigrationReadinessCategory.PLANNED,
                    signal,
                    "Maven exec-shaped plugin (exec/frontend/antrun)",
                    "[generated.tools] exec step",
                    "",
                    signal.nextStep());
            case "maven.plugin.exec-nondeterministic" -> MigrationReadinessFindings.finding(
                    "generated-sources",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "Maven database-backed codegen plugin",
                    "committed DDL or cache = \"none\"",
                    "",
                    signal.nextStep());
            case "maven.plugin.exec-unmappable" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "Maven exec plugin with shell or control flow",
                    "[tasks.<id>] or CI",
                    "",
                    signal.nextStep());
            case "maven.reactor.detected" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.PLANNED,
                    signal,
                    "multi-module reactor (pom aggregator with <modules>)",
                    "[workspace] with one member draft per module",
                    "",
                    signal.nextStep());
            case "maven.annotation-processor.path" -> MigrationReadinessFindings.finding(
                    "generated-sources",
                    MigrationReadinessCategory.PLANNED,
                    signal,
                    "maven-compiler-plugin annotationProcessorPaths",
                    "[dependencies.processor]",
                    "",
                    signal.nextStep());
            case "maven.parent.snapshot" -> MigrationReadinessFindings.finding(
                    "repositories",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "SNAPSHOT Maven parent",
                    "fixed parent metadata and [repositories]",
                    "",
                    signal.nextStep());
            case "maven.parent.unresolved" -> MigrationReadinessFindings.finding(
                    "repositories",
                    MigrationReadinessCategory.UNKNOWN,
                    signal,
                    "unresolved Maven parent metadata",
                    "fixed parent metadata and [repositories]",
                    "",
                    signal.nextStep());
            case "maven.java-version.unknown" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.UNKNOWN,
                    signal,
                    "unknown Maven Java version",
                    "[project].java",
                    "",
                    signal.nextStep());
            case "maven.test-java-version.divergent" -> MigrationReadinessFindings.finding(
                    "tests",
                    MigrationReadinessCategory.UNKNOWN,
                    signal,
                    "divergent Maven test Java version",
                    "test compile settings",
                    "",
                    signal.nextStep());
            case "maven.repository.declared" -> MigrationReadinessFindings.finding(
                    "repositories",
                    MigrationReadinessCategory.PLANNED,
                    signal,
                    "declared Maven repository or pluginRepository",
                    "[repositories]",
                    "",
                    signal.nextStep());
            case "maven.repository.snapshots-enabled" -> MigrationReadinessFindings.finding(
                    "repositories",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "snapshots-enabled Maven repository",
                    "[repositories]",
                    "",
                    signal.nextStep());
            case "maven.profile.detected" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.NON_DETERMINISTIC,
                    signal,
                    "Maven profile activation",
                    "execution context policy",
                    "",
                    signal.nextStep());
            case "maven.profile.modules.detected" -> MigrationReadinessFindings.finding(
                    "dependencies",
                    MigrationReadinessCategory.BLOCKED,
                    signal,
                    "profile-declared Maven modules omitted from workspace emit",
                    "[workspace] reviewed members",
                    "",
                    signal.nextStep());
            case "maven.language.unsupported" -> MigrationReadinessFindings.finding(
                    "ci",
                    MigrationReadinessCategory.UNSUPPORTED,
                    signal,
                    "Maven unsupported language or Android plugin",
                    "normal Java application modules",
                    "",
                    signal.nextStep());
            case "maven.framework-native.unsupported" -> MigrationReadinessFindings.finding(
                    "package",
                    MigrationReadinessCategory.UNSUPPORTED,
                    signal,
                    "Maven framework-native plugin behavior",
                    "typed Zolt framework settings",
                    "",
                    signal.nextStep());
            default -> MigrationReadinessFindings.generic(signal);
        };
    }

    private static MigrationReadinessFinding mapPlugin(
            ExplainSignal signal,
            MigrationReadinessCategory category,
            String fallbackPattern) {
        String coordinate = pluginCoordinate(signal);
        if (matchesAny(coordinate, "jacoco-maven-plugin", "cobertura-maven-plugin")) {
            return MigrationReadinessFindings.finding(
                    "coverage",
                    category,
                    signal,
                    "Maven coverage plugin",
                    "zolt coverage",
                    "",
                    "Map Maven coverage configuration to explicit Zolt coverage commands and reports.");
        }
        if (matchesAny(coordinate,
                "maven-jar-plugin",
                "maven-war-plugin",
                "maven-assembly-plugin",
                "maven-shade-plugin",
                "maven-bundle-plugin",
                "spring-boot-maven-plugin")) {
            return MigrationReadinessFindings.finding(
                    "package",
                    category,
                    signal,
                    "Maven package plugin",
                    "[package]",
                    "",
                    "Model the Maven packaging plugin configuration as explicit Zolt package settings.");
        }
        if (matchesAny(coordinate,
                "maven-deploy-plugin",
                "maven-gpg-plugin",
                "maven-scm-publish-plugin",
                "nexus-staging-maven-plugin",
                "central-publishing-maven-plugin")) {
            return MigrationReadinessFindings.finding(
                    "publish",
                    category,
                    signal,
                    "Maven publish/signing plugin",
                    "[publish] and zolt publish --dry-run",
                    "",
                    "Map Maven publication, signing, and deployment metadata to Zolt publish settings and credential policy.");
        }
        if (matchesAny(coordinate,
                "maven-surefire-plugin",
                "maven-failsafe-plugin",
                "junit-platform-maven-plugin")) {
            return MigrationReadinessFindings.finding(
                    "tests",
                    category,
                    signal,
                    "Maven test execution plugin",
                    "[test] and integration-test settings",
                    "",
                    "Map Maven test execution settings to explicit Zolt test runtime configuration.");
        }
        if (matchesAny(coordinate,
                "maven-compiler-plugin",
                "build-helper-maven-plugin")) {
            return MigrationReadinessFindings.finding(
                    "tests",
                    category,
                    signal,
                    "Maven compiler or source-root plugin",
                    "Java compile/test-compile settings",
                    "",
                    "Map Maven compiler and source-root settings to explicit Zolt Java and test source configuration.");
        }
        if (matchesAny(coordinate,
                "antlr4-maven-plugin",
                "javacc-maven-plugin",
                "ph-javacc-maven-plugin",
                "openapi-generator-maven-plugin",
                "protobuf-maven-plugin",
                "jaxb2-maven-plugin",
                "jaxb-maven-plugin",
                "jooq-codegen-maven")) {
            return MigrationReadinessFindings.finding(
                    "generated-sources",
                    category,
                    signal,
                    "Maven generated-source plugin",
                    "[generated.main] and [generated.test]",
                    "",
                    "Model generated sources as typed Zolt generated-source steps: the openapi/protobuf built-ins,"
                            + " or a jvm exec tool under [generated.tools] (using the plugin's own dependency"
                            + " coordinates) with explicit inputs and output.");
        }
        return MigrationReadinessFindings.finding(
                "ci",
                category,
                signal,
                fallbackPattern,
                "explicit Zolt command or project model",
                "",
                signal.nextStep());
    }

    private static String pluginCoordinate(ExplainSignal signal) {
        String prefix = "Plugin `";
        int start = signal.message().indexOf(prefix);
        if (start < 0) {
            return "";
        }
        int coordinateStart = start + prefix.length();
        int coordinateEnd = signal.message().indexOf('`', coordinateStart);
        if (coordinateEnd <= coordinateStart) {
            return "";
        }
        return signal.message().substring(coordinateStart, coordinateEnd).toLowerCase(Locale.ROOT);
    }

    private static boolean matchesAny(String coordinate, String... artifactIds) {
        for (String artifactId : artifactIds) {
            if (coordinate.contains(":" + artifactId) || coordinate.endsWith(artifactId)) {
                return true;
            }
        }
        return false;
    }
}
