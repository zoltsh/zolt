package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.gradle.GradleProjectInspection;
import sh.zolt.explain.gradle.GradleStaticProjectInspector;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.authored.AuthoredDependency;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GradleKotlinDraftAssessorTest {
    private static final String VERSION = "2.3.21";
    private static final String STDLIB = "org.jetbrains.kotlin:kotlin-stdlib";

    @TempDir
    private Path tempDir;

    @Test
    void acceptsStrictKotlinOnlyProjectAndMaterializesGradleDefaultStdlib() throws IOException {
        Path root = project("eligible", build(VERSION, "", ""));
        kotlinRoots(root, true, true);

        Assessment assessment = assess(root);
        KotlinJvmDraftEligibility.Decision.Eligible eligible = assertInstanceOf(
                KotlinJvmDraftEligibility.Decision.Eligible.class,
                assessment.result().decision());

        assertEquals(VERSION, eligible.version().value());
        assertTrue(eligible.main());
        assertTrue(eligible.test());
        List<AuthoredDependency> stdlib = assessment.dependencies().ordinaryCandidates(STDLIB);
        assertEquals(1, stdlib.size());
        assertEquals(DependencyLane.IMPLEMENTATION, stdlib.getFirst().lane());
        assertEquals(
                VERSION,
                assertInstanceOf(DependencySelector.FixedVersion.class,
                        stdlib.getFirst().selector()).value());
        assertTrue(assessment.result().gradleReason().isEmpty());
        assertTrue(assessment.result().reviewNote().isEmpty());
    }

    @Test
    void acceptsExplicitAlignedStdlibWhenGradleDefaultIsDisabled() throws IOException {
        Path root = project("explicit-stdlib", build(
                VERSION,
                "implementation 'org.jetbrains.kotlin:kotlin-stdlib:" + VERSION + "'",
                ""));
        Files.writeString(root.resolve("gradle.properties"),
                "kotlin.stdlib.default.dependency=false\n");
        kotlinRoots(root, true, false);

        Assessment assessment = assess(root);

        assertInstanceOf(
                KotlinJvmDraftEligibility.Decision.Eligible.class,
                assessment.result().decision(),
                () -> assessment.result().toString());
        assertEquals(1, assessment.dependencies().ordinaryCandidates(STDLIB).size());
    }

    @Test
    void acceptsLiteralKotlinDslProjectShape() throws IOException {
        Path root = tempDir.resolve("kotlin-dsl");
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.gradle.kts"),
                "rootProject.name = \"kotlin-dsl\"\n");
        Files.writeString(root.resolve("build.gradle.kts"), """
                plugins {
                    kotlin("jvm") version "2.3.21"
                    application
                }
                group = "com.example"
                version = "1.0.0"
                repositories {
                    mavenCentral()
                }
                java {
                    toolchain {
                        languageVersion.set(JavaLanguageVersion.of(21))
                    }
                }
                dependencies {
                }
                application {
                    mainClass.set("p.MainKt")
                }
                """);
        kotlinRoots(root, true, false);

        Assessment assessment = assess(root);

        assertInstanceOf(
                KotlinJvmDraftEligibility.Decision.Eligible.class,
                assessment.result().decision(),
                () -> assessment.result().toString());
    }

    @Test
    void rejectsUnpreservedPluginModuleAndCompilerShapes() throws IOException {
        Path currentModuleIdentity = project("kgp-24", build("2.4.20", "", ""));
        kotlinRoots(currentModuleIdentity, true, false);
        assertReason(assess(currentModuleIdentity), GradleKotlinDraftReason.PLUGIN_VERSION);

        Path compilerControls = project("compiler-controls", build(
                VERSION,
                "",
                """
                kotlin {
                    compilerOptions {
                        allWarningsAsErrors = true
                    }
                }
                """));
        kotlinRoots(compilerControls, true, false);
        assertReason(assess(compilerControls), GradleKotlinDraftReason.KOTLIN_EXTENSION);

        Path otherPlugin = project("other-plugin", build(
                VERSION,
                "",
                ""));
        replaceBuild(otherPlugin, buildWithPlugins(VERSION, "id 'org.springframework.boot' version '3.5.6'"));
        kotlinRoots(otherPlugin, true, false);
        assertReason(assess(otherPlugin), GradleKotlinDraftReason.OTHER_APPLIED_PLUGIN);

        Path settingsPlugin = project("settings-plugin", build(VERSION, "", ""));
        Files.writeString(settingsPlugin.resolve("settings.gradle"), """
                plugins {
                    id 'com.gradle.develocity' version '4.2'
                }
                rootProject.name = 'settings-plugin'
                """);
        kotlinRoots(settingsPlugin, true, false);
        assertReason(assess(settingsPlugin), GradleKotlinDraftReason.SETTINGS_SHAPE);

        Path executableLogic = project("executable-logic", build(
                VERSION,
                "",
                "tasks.register('generated') {}"));
        kotlinRoots(executableLogic, true, false);
        assertReason(assess(executableLogic), GradleKotlinDraftReason.BUILD_SHAPE);
    }

    @Test
    void rejectsUnprovenSourceAndRuntimeBoundaries() throws IOException {
        Path mixedJava = project("mixed-java", build(VERSION, "", ""));
        kotlinRoots(mixedJava, true, false);
        Files.createDirectories(mixedJava.resolve("src/main/java/p"));
        Files.writeString(mixedJava.resolve("src/main/java/p/JavaPeer.java"),
                "package p; class JavaPeer {}\n");
        assertReason(assess(mixedJava), GradleKotlinDraftReason.JAVA_SOURCES);

        Path noStdlib = project("no-stdlib", build(VERSION, "", ""));
        Files.writeString(noStdlib.resolve("gradle.properties"),
                "kotlin.stdlib.default.dependency=false\n");
        kotlinRoots(noStdlib, true, false);
        assertReason(assess(noStdlib), GradleKotlinDraftReason.STDLIB_DEFAULT);

        Path legacyStdlib = project("legacy-stdlib", build(
                VERSION,
                "implementation 'org.jetbrains.kotlin:kotlin-stdlib-jdk8:" + VERSION + "'",
                ""));
        kotlinRoots(legacyStdlib, true, false);
        assertReason(assess(legacyStdlib), GradleKotlinDraftReason.STDLIB_SHAPE);

        Path platform = project("platform", build(
                VERSION,
                "implementation platform('org.jetbrains.kotlin:kotlin-bom:" + VERSION + "')",
                ""));
        kotlinRoots(platform, true, false);
        assertReason(assess(platform), GradleKotlinDraftReason.PLATFORM_DEPENDENCY);

        Path forced = project("forced", build(
                VERSION,
                "",
                """
                configurations.all {
                    resolutionStrategy.force 'org.jetbrains.kotlin:kotlin-stdlib:2.2.21'
                }
                """));
        kotlinRoots(forced, true, false);
        assertReason(assess(forced), GradleKotlinDraftReason.DEPENDENCY_RESOLUTION);
    }

    @Test
    void preservesSharedStdlibVersionMismatchReason() throws IOException {
        Path root = project("mismatch", build(
                VERSION,
                "implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.2.21'",
                ""));
        kotlinRoots(root, true, false);

        Assessment assessment = assess(root);
        KotlinJvmDraftEligibility.Decision.NeedsReview review = assertInstanceOf(
                KotlinJvmDraftEligibility.Decision.NeedsReview.class,
                assessment.result().decision());

        assertEquals(KotlinJvmDraftEligibility.Reason.STDLIB_VERSION_MISMATCH, review.reason());
        assertTrue(assessment.result().gradleReason().isEmpty());
        assertTrue(assessment.result().reviewNote().orElseThrow()
                .contains("compiler and kotlin-stdlib versions differed"));
    }

    private Assessment assess(Path root) {
        GradleProjectInspection project = new GradleStaticProjectInspector()
                .inspect(root)
                .projects()
                .getFirst();
        List<String> notes = new ArrayList<>();
        DraftDependencies dependencies = new DraftDependencies(notes);
        new GradleDependencySectionMapper(dependencies, null, notes).map(project.dependencies());
        return new Assessment(
                dependencies,
                GradleKotlinDraftAssessor.assess(project, dependencies));
    }

    private Path project(String directory, String build) throws IOException {
        Path root = tempDir.resolve(directory);
        Files.createDirectories(root);
        Files.writeString(root.resolve("settings.gradle"),
                "rootProject.name = '" + directory + "'\n");
        Files.writeString(root.resolve("build.gradle"), build);
        return root;
    }

    private static void replaceBuild(Path root, String build) throws IOException {
        Files.writeString(root.resolve("build.gradle"), build);
    }

    private static String build(
            String kotlinVersion,
            String dependencies,
            String extra) {
        return """
                plugins {
                    id 'org.jetbrains.kotlin.jvm' version '%s'
                }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                dependencies {
                    %s
                }
                %s
                """.formatted(kotlinVersion, dependencies, extra);
    }

    private static String buildWithPlugins(
            String kotlinVersion,
            String otherPlugin) {
        return """
                plugins {
                    id 'org.jetbrains.kotlin.jvm' version '%s'
                    %s
                }
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                """.formatted(kotlinVersion, otherPlugin);
    }

    private static void kotlinRoots(
            Path root,
            boolean main,
            boolean test) throws IOException {
        if (main) {
            Files.createDirectories(root.resolve("src/main/kotlin/p"));
            Files.writeString(root.resolve("src/main/kotlin/p/Main.kt"), "package p\nclass Main\n");
        }
        if (test) {
            Files.createDirectories(root.resolve("src/test/kotlin/p"));
            Files.writeString(root.resolve("src/test/kotlin/p/MainTest.kt"), "package p\nclass MainTest\n");
        }
    }

    private static void assertReason(
            Assessment assessment,
            GradleKotlinDraftReason reason) {
        assertInstanceOf(
                KotlinJvmDraftEligibility.Decision.NeedsReview.class,
                assessment.result().decision());
        assertEquals(reason, assessment.result().gradleReason().orElseThrow());
        assertTrue(assessment.result().reviewNote().isPresent());
    }

    private record Assessment(
            DraftDependencies dependencies,
            GradleKotlinDraftAssessor.Result result) {
    }
}
