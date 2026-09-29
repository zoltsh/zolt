package sh.zolt.explain.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.explain.ExplainSignal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GradleBuildFileParserTest {
    private final GradleBuildFileParser parser = new GradleBuildFileParser();

    @Test
    void parsesPluginsRepositoriesAndJavaVersion() {
        String content = """
                plugins {
                    id 'org.springframework.boot' version '3.3.6'
                    id("java-library")
                    jacoco
                }

                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                    maven { url = 'https://repo.example.com/releases' }
                }

                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                """;

        assertEquals("21", parser.javaVersion(content));
        assertTrue(parser.plugins(content).stream()
                .anyMatch(plugin -> plugin.id().equals("org.springframework.boot") && plugin.version().equals("3.3.6")));
        assertTrue(parser.plugins(content).stream().anyMatch(plugin -> plugin.id().equals("java-library")));
        assertTrue(parser.plugins(content).stream().anyMatch(plugin -> plugin.id().equals("jacoco")));
        assertTrue(parser.repositories(content).stream()
                .anyMatch(repository -> repository.kind().equals("mavenCentral")
                        && repository.url().equals("https://repo.maven.apache.org/maven2")));
        assertTrue(parser.repositories(content).stream()
                .anyMatch(repository -> repository.kind().equals("maven")
                        && repository.url().equals("https://repo.example.com/releases")));
    }

    @Test
    void parsesLegacyJavaVersionNotationBeforeEmitNormalization() {
        assertEquals("1.8", parser.javaVersion("sourceCompatibility = 1.8"));
        assertEquals("1.8", parser.javaVersion("targetCompatibility = 1.8"));
        assertEquals("1.8", parser.javaVersion("sourceCompatibility = JavaVersion.VERSION_1_8"));
        assertEquals("17", parser.javaVersion("targetCompatibility = JavaVersion.VERSION_17"));
    }

    @Test
    void parsesKotlinDslBacktickAccessorPlugins() {
        String content = """
                plugins {
                    `java-library`
                    `application`
                    id("org.springframework.boot") version "3.3.6"
                }
                """;

        var plugins = parser.plugins(content);

        assertTrue(plugins.stream().anyMatch(plugin -> plugin.id().equals("java-library")),
                () -> "backtick `java-library` should be detected: " + plugins);
        assertTrue(plugins.stream().anyMatch(plugin -> plugin.id().equals("application")),
                () -> "backtick `application` should be detected: " + plugins);
        assertTrue(plugins.stream().anyMatch(plugin -> plugin.id().equals("org.springframework.boot")));
    }

    @Test
    void parsesKotlinDslPluginAccessorsAsCanonicalPluginIds() {
        String content = """
                plugins {
                    kotlin("jvm") version "2.2.0"
                    kotlin("multiplatform") version "2.1.21"
                    kotlin("plugin.serialization")
                }
                """;

        assertEquals(
                List.of(
                        new GradlePluginInspection("org.jetbrains.kotlin.jvm", "2.2.0"),
                        new GradlePluginInspection("org.jetbrains.kotlin.multiplatform", "2.1.21"),
                        new GradlePluginInspection("org.jetbrains.kotlin.plugin.serialization", "")),
                parser.plugins(content));
    }

    @Test
    void doesNotTreatKotlinCallsOutsidePluginsAsPluginAccessors() {
        String content = """
                plugins { java }
                dependencies {
                    implementation(kotlin("stdlib"))
                }
                kotlin {
                    jvmToolchain(21)
                }
                """;

        List<ExplainSignal> signals = new ArrayList<>();
        assertEquals(
                List.of(new GradlePluginInspection("java", "")),
                parser.plugins(content, Map.of(), ".", signals));
        assertTrue(signals.isEmpty(), () -> "non-plugin Kotlin calls must not emit signals: " + signals);
    }

    @Test
    void parsesDependencyNotationsAndCatalogAliases() {
        String content = """
                dependencies {
                    api 'com.example:api:1.0'
                    implementation libs.guava
                    compileOnly group: 'jakarta.annotation', name: 'jakarta.annotation-api', version: '3.0.0'
                    testImplementation(libs.junit.jupiter)
                    annotationProcessor("org.mapstruct:mapstruct-processor:1.6.3")
                }
                """;

        var dependencies = parser.dependencies(content, Map.of(
                "guava", "com.google.guava:guava:33.4.8-jre",
                "junit.jupiter", "org.junit.jupiter:junit-jupiter:5.11.4"),
                Map.of(), ".", new java.util.ArrayList<>());

        assertTrue(dependencies.stream().anyMatch(dependency ->
                dependency.configuration().equals("api")
                        && dependency.resolvedCoordinate().equals("com.example:api:1.0")));
        assertTrue(dependencies.stream().anyMatch(dependency ->
                dependency.configuration().equals("implementation")
                        && dependency.versionCatalogAlias().equals("guava")
                        && dependency.resolvedCoordinate().equals("com.google.guava:guava:33.4.8-jre")));
        assertTrue(dependencies.stream().anyMatch(dependency ->
                dependency.configuration().equals("compileOnly")
                        && dependency.resolvedCoordinate().equals("jakarta.annotation:jakarta.annotation-api:3.0.0")));
        assertTrue(dependencies.stream().anyMatch(dependency ->
                dependency.configuration().equals("testImplementation")
                        && dependency.versionCatalogAlias().equals("junit.jupiter")));
        assertTrue(dependencies.stream().anyMatch(dependency ->
                dependency.configuration().equals("annotationProcessor")
                        && dependency.resolvedCoordinate().equals("org.mapstruct:mapstruct-processor:1.6.3")));
    }

    @Test
    void interpolatesExtAndGradlePropertiesInDependencyVersions() {
        String content = """
                ext {
                    slf4jVersion = '2.0.13'
                    junitVersion = "5.10.2"
                }

                dependencies {
                    implementation "org.slf4j:slf4j-api:$slf4jVersion"
                    implementation group: 'com.google.code.gson', name: 'gson', version: '${gsonVersion}'
                    testImplementation "org.junit.jupiter:junit-jupiter:$junitVersion"
                }
                """;
        Map<String, String> properties = new java.util.LinkedHashMap<>(parser.extProperties(content));
        properties.put("gsonVersion", "2.11.0");

        var dependencies = parser.dependencies(
                content,
                Map.of(),
                Map.of(),
                properties,
                ".",
                new java.util.ArrayList<>());

        assertTrue(dependencies.stream()
                .anyMatch(dependency -> dependency.resolvedCoordinate().equals("org.slf4j:slf4j-api:2.0.13")));
        assertTrue(dependencies.stream()
                .anyMatch(dependency -> dependency.resolvedCoordinate().equals("com.google.code.gson:gson:2.11.0")));
        assertTrue(dependencies.stream()
                .anyMatch(dependency -> dependency.resolvedCoordinate().equals("org.junit.jupiter:junit-jupiter:5.10.2")));
    }

    @Test
    void unresolvedDependencyVersionPlaceholdersBecomeBlockerSignals() {
        String content = """
                dependencies {
                    implementation "org.slf4j:slf4j-api:$slf4jVersion"
                    implementation "com.google.code.gson:gson:${gsonVersion}"
                }
                """;
        var signals = new java.util.ArrayList<sh.zolt.explain.ExplainSignal>();

        var dependencies = parser.dependencies(content, Map.of(), Map.of(), Map.of(), ".", signals);

        assertTrue(dependencies.isEmpty(), () -> "unresolved placeholders must not be emitted: " + dependencies);
        assertEquals(2, signals.size(), () -> "both placeholder forms need blocker signals: " + signals);
        assertTrue(signals.stream().allMatch(signal -> signal.id().equals("gradle.dependency.dynamic-version")));
        assertTrue(signals.stream().allMatch(signal -> signal.message().contains("version-policy rule: no-interpolation")));
    }

    @Test
    void buildscriptDependenciesAndRepositoriesDoNotPolluteProjectFacts() {
        String content = """
                buildscript {
                    repositories {
                        mavenLocal()
                    }
                    dependencies {
                        classpath 'com.example:gradle-plugin:1.0'
                    }
                }

                dependencies {
                    implementation 'com.example:app-lib:1.0'
                }

                repositories {
                    mavenCentral()
                }
                """;

        var dependencies = parser.dependencies(content, Map.of(), Map.of(), ".", new java.util.ArrayList<>());
        var repositories = parser.repositories(content);

        assertEquals(1, dependencies.size(), () -> "only project-scope dependency should be read: " + dependencies);
        assertEquals("com.example:app-lib:1.0", dependencies.getFirst().resolvedCoordinate());
        assertTrue(repositories.stream().anyMatch(repository -> repository.kind().equals("mavenCentral")));
        assertFalse(repositories.stream().anyMatch(repository -> repository.kind().equals("mavenLocal")),
                () -> "buildscript mavenLocal must not be reported as a project repository: " + repositories);
    }

    @Test
    void readsEveryTopLevelDependenciesBlockAndEveryQuotedCoordinateOnALine() {
        String content = """
                dependencies {
                    implementation 'com.example:first:1.0'
                }

                dependencies {
                    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4", "org.junit.jupiter:junit-jupiter-engine:5.11.4")
                }
                """;

        var dependencies = parser.dependencies(content, Map.of(), Map.of(), ".", new java.util.ArrayList<>());

        assertEquals(3, dependencies.size(), () -> "expected all top-level blocks and quoted coordinates: " + dependencies);
        assertTrue(dependencies.stream()
                .anyMatch(dependency -> dependency.resolvedCoordinate().equals("com.example:first:1.0")));
        assertTrue(dependencies.stream()
                .anyMatch(dependency -> dependency.resolvedCoordinate().equals("org.junit.jupiter:junit-jupiter-api:5.11.4")));
        assertTrue(dependencies.stream()
                .anyMatch(dependency -> dependency.resolvedCoordinate().equals("org.junit.jupiter:junit-jupiter-engine:5.11.4")));
    }

    @Test
    void dropsConstraintAndNonLiteralDependencyExpressions() {
        String content = """
                dependencies {
                    implementation 'com.example:real:1.0'
                    constraints {
                        implementation 'com.example:constrained:2.0'
                    }
                    implementation it
                    testImplementation project.rootProject
                }
                """;
        var signals = new java.util.ArrayList<sh.zolt.explain.ExplainSignal>();

        var dependencies = parser.dependencies(content, Map.of(), Map.of(), ".", signals);

        assertEquals(1, dependencies.size(), () -> "constraints and dynamic loop variables must not be fabricated: " + dependencies);
        assertEquals("com.example:real:1.0", dependencies.getFirst().resolvedCoordinate());
        assertEquals(2, signals.size(), () -> "non-literal dependency expressions should be reported as unknown: " + signals);
        assertTrue(signals.stream().allMatch(signal ->
                signal.severity() == sh.zolt.explain.ExplainSignal.Severity.UNKNOWN
                        && signal.id().equals("gradle.dependency.unresolved-notation")));
    }

    @Test
    void configurationNameSubstringInArtifactIdDoesNotSpawnPhantomDependency() {
        String content = """
                dependencies {
                    implementation 'org.slf4j:slf4j-api:2.0.16'
                    implementation 'jakarta.annotation:jakarta.annotation-api:3.0.0'
                }
                """;

        var dependencies = parser.dependencies(content, Map.of(), Map.of(), ".", new java.util.ArrayList<>());

        assertEquals(2, dependencies.size(), () -> "expected exactly two real dependencies, got " + dependencies);
        assertTrue(dependencies.stream().allMatch(dependency -> dependency.configuration().equals("implementation")),
                () -> "no dependency should be attributed to the `api` config from the `slf4j-api` substring: " + dependencies);
        assertTrue(dependencies.stream()
                .anyMatch(dependency -> dependency.resolvedCoordinate().equals("org.slf4j:slf4j-api:2.0.16")));
        assertTrue(dependencies.stream()
                .anyMatch(dependency -> dependency.resolvedCoordinate().equals("jakarta.annotation:jakarta.annotation-api:3.0.0")));
        assertTrue(dependencies.stream().noneMatch(dependency -> dependency.configuration().equals("api")),
                () -> "phantom api-config entry leaked from an artifact-id substring: " + dependencies);
    }

    @Test
    void parsesGroupVersionAndMainClassAssignments() {
        String content = """
                group = 'com.example'
                version = '0.3.1'

                application {
                    mainClass = 'com.example.report.ReportApp'
                }
                """;

        assertEquals("com.example", parser.group(content).orElseThrow());
        assertEquals("0.3.1", parser.version(content).orElseThrow());
        assertEquals("com.example.report.ReportApp", parser.mainClass(content).orElseThrow());
    }

    @Test
    void taskLevelGroupAndMainClassAssignmentsDoNotLeakIntoProjectFacts() {
        String content = """
                group = "com.example"

                application {
                    mainClass = "com.example.App"
                }

                tasks.register<JavaExec>("memoryOverhead") {
                    group = "Benchmarks"
                    mainClass = "com.example.bench.MemoryOverhead"
                }
                """;

        assertEquals("com.example", parser.group(content).orElseThrow());
        assertEquals("com.example.App", parser.mainClass(content).orElseThrow());
    }

    @Test
    void parsesGroovySpaceCallAssignmentsAndMainClassName() {
        String content = """
                group "com.example.groovy"
                version "1.2.3"
                mainClassName = 'com.example.Legacy'
                """;

        assertEquals("com.example.groovy", parser.group(content).orElseThrow());
        assertEquals("1.2.3", parser.version(content).orElseThrow());
        assertEquals("com.example.Legacy", parser.mainClass(content).orElseThrow());
    }

    @Test
    void ignoresInterpolatedGroupAndAbsentFields() {
        String content = """
                group "${applicationGroupId}"
                dependencies {
                    implementation 'com.example:lib:1.0'
                }
                """;

        assertTrue(parser.group(content).isEmpty(), () -> "interpolated group must not be read as a literal");
        assertTrue(parser.version(content).isEmpty());
        assertTrue(parser.mainClass(content).isEmpty());
    }

    @Test
    void arbitraryUriAssignmentsAreNotRepositories() {
        String content = """
                extra["license"] = uri("https://www.eclipse.org/legal/epl-2.0")

                repositories {
                    maven {
                        url = uri("https://repo.example.com/releases")
                    }
                }
                """;

        var repositories = parser.repositories(content);

        assertEquals(1, repositories.size(), () -> "only repositories block URLs should be read: " + repositories);
        assertEquals("https://repo.example.com/releases", repositories.getFirst().url());
    }

    @Test
    void parsesSourceRootsWithDefaultsForAdditiveSourceDirs() {
        String content = """
                sourceSets {
                    main {
                        java {
                            srcDirs = ['src/main/java', 'build/generated/sources/openapi']
                        }
                    }
                    test {
                        java {
                            srcDirs += ['src/integrationTest/java']
                        }
                    }
                }
                """;

        assertEquals(
                List.of("src/main/java", "build/generated/sources/openapi"),
                parser.sourceRoots(content, "main", "src/main/java"));
        assertEquals(
                List.of("src/test/java", "src/integrationTest/java"),
                parser.sourceRoots(content, "test", "src/test/java"));
        assertEquals(
                List.of("src/fixtures/java"),
                parser.sourceRoots(content, "fixtures", "src/fixtures/java"));
        assertEquals(
                List.of("src/integrationTest/java"),
                parser.sourceRoots(content, "test", "src/test/java", false));
        assertEquals(
                List.of(),
                parser.sourceRoots(content, "fixtures", "src/fixtures/java", false));
    }
}
