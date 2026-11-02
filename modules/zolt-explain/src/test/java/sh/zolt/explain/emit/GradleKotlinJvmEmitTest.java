package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.gradle.GradleStaticProjectInspector;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredCompiler;
import sh.zolt.manifest.authored.AuthoredTests;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class GradleKotlinJvmEmitTest {
    private static final String VERSION = "2.3.21";
    private static final String STDLIB = "org.jetbrains.kotlin:kotlin-stdlib";

    @TempDir
    private Path tempDir;
    private final InspectionToManifest mapper = new InspectionToManifest();

    @Test
    void emitsQualifiedGroovyDslMainAndTestKotlinWithDefaultStdlib() throws IOException {
        Path root = groovyProject("gradle-kotlin", VERSION, "");
        kotlinSources(root, true, true);

        DraftZoltToml draft = draft(root);
        DraftManifestSubject subject = DraftManifestSubject.of(draft);

        assertEquals(VERSION, draft.manifest().toolchains().kotlin().orElseThrow().version().value());
        AuthoredBuild build = draft.manifest().build().build().orElseThrow();
        assertEquals(List.of("src/main/kotlin"), paths(build.sources()));
        AuthoredTests.Sources tests = draft.manifest().build().tests().orElseThrow()
                .sources().orElseThrow();
        assertTrue(tests.java().isEmpty());
        assertEquals(List.of("src/test/kotlin"), paths(tests.kotlin()));
        assertEquals(VERSION, subject.fixed(DependencyLane.IMPLEMENTATION).get(STDLIB));
        AuthoredCompiler compiler = draft.manifest().build().compiler().orElseThrow();
        assertEquals("UTF8", compiler.encoding().orElseThrow());
        assertEquals("gradle-kotlin", compiler.kotlinModule().orElseThrow());
        assertEquals(
                "gradle-kotlin_test",
                compiler.test().orElseThrow().kotlinModule().orElseThrow());
        assertFalse(draft.notes().stream().anyMatch(note ->
                note.contains("Kotlin/JVM roots were not emitted")
                        || note.contains("cannot migrate automatically")), draft.notes()::toString);
    }

    @Test
    void emitsQualifiedTestOnlyKotlinWithExplicitRuntime() throws IOException {
        Path root = groovyProject(
                "test-only",
                VERSION,
                "testImplementation 'org.jetbrains.kotlin:kotlin-stdlib:" + VERSION + "'");
        Files.writeString(root.resolve("gradle.properties"),
                "kotlin.stdlib.default.dependency=false\n");
        kotlinSources(root, false, true);

        DraftZoltToml draft = draft(root);

        assertTrue(draft.manifest().build().build().isEmpty());
        assertEquals(VERSION, draft.manifest().toolchains().kotlin().orElseThrow().version().value());
        assertEquals(
                List.of("src/test/kotlin"),
                paths(draft.manifest().build().tests().orElseThrow()
                        .sources().orElseThrow().kotlin()));
        assertEquals(VERSION, DraftManifestSubject.of(draft).fixed(DependencyLane.TEST).get(STDLIB));
        AuthoredCompiler compiler = draft.manifest().build().compiler().orElseThrow();
        assertTrue(compiler.kotlinModule().isEmpty());
        assertEquals(
                "test-only_test",
                compiler.test().orElseThrow().kotlinModule().orElseThrow());
    }

    @Test
    void emitsQualifiedKotlinDslProject() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("kotlin-dsl"));
        Files.writeString(root.resolve("settings.gradle.kts"),
                "rootProject.name = \"kotlin-dsl\"\n");
        Files.writeString(root.resolve("build.gradle.kts"), """
                plugins {
                    kotlin("jvm") version "2.3.21"
                }
                group = "com.example"
                version = "1.0.0"
                java {
                    toolchain {
                        languageVersion.set(JavaLanguageVersion.of(21))
                    }
                }
                dependencies {
                }
                """);
        kotlinSources(root, true, false);

        DraftZoltToml draft = draft(root);

        assertEquals(VERSION, draft.manifest().toolchains().kotlin().orElseThrow().version().value());
        assertEquals(
                "kotlin-dsl",
                draft.manifest().build().compiler().orElseThrow().kotlinModule().orElseThrow());
        assertEquals(VERSION, DraftManifestSubject.of(draft)
                .fixed(DependencyLane.IMPLEMENTATION).get(STDLIB));
    }

    @Test
    void keepsKotlinTwentyFourAndMixedJavaReviewOnly() throws IOException {
        Path currentIdentity = groovyProject("kgp-24", "2.4.20", "");
        kotlinSources(currentIdentity, true, false);
        assertReviewOnly(draft(currentIdentity), "not a fixed qualified 2.2 or 2.3 release");

        Path mixed = groovyProject("mixed-java", VERSION, "");
        kotlinSources(mixed, true, false);
        Path java = mixed.resolve("src/main/java/p/JavaPeer.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "package p; class JavaPeer {}\n");
        assertReviewOnly(draft(mixed), "mixed Java sources require Gradle javac controls");
    }

    @Test
    void keepsUnmappedDependencyShapesReviewOnly() throws IOException {
        Path root = groovyProject(
                "local-dependency",
                VERSION,
                "implementation files('libs/local.jar')");
        kotlinSources(root, true, false);

        assertReviewOnly(draft(root), "dependency declarations were not fully mapped");
    }

    private DraftZoltToml draft(Path root) {
        return mapper.fromGradle(new GradleStaticProjectInspector().inspect(root));
    }

    private static void assertReviewOnly(DraftZoltToml draft, String reason) {
        assertTrue(draft.manifest().toolchains().kotlin().isEmpty(),
                () -> "unexpected Kotlin toolchain: " + draft.manifest().toolchains());
        assertTrue(draft.manifest().build().tests().isEmpty());
        assertTrue(draft.manifest().build().build().stream()
                .flatMap(build -> build.sources().stream())
                .noneMatch(root -> root.value().contains("kotlin")));
        assertTrue(draft.notes().stream().anyMatch(note -> note.contains(reason)), draft.notes()::toString);
    }

    private Path groovyProject(
            String name,
            String kotlinVersion,
            String dependencies) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(root.resolve("settings.gradle"),
                "rootProject.name = '" + name + "'\n");
        Files.writeString(root.resolve("build.gradle"), """
                plugins {
                    id 'org.jetbrains.kotlin.jvm' version '%s'
                }
                group = 'com.example'
                version = '1.0.0'
                java {
                    toolchain {
                        languageVersion = JavaLanguageVersion.of(21)
                    }
                }
                dependencies {
                    %s
                }
                """.formatted(kotlinVersion, dependencies));
        return root;
    }

    private static void kotlinSources(
            Path root,
            boolean main,
            boolean test) throws IOException {
        if (main) {
            Path source = root.resolve("src/main/kotlin/p/Main.kt");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package p\nclass Main\n");
        }
        if (test) {
            Path source = root.resolve("src/test/kotlin/p/MainTest.kt");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package p\nclass MainTest\n");
        }
    }

    private static List<String> paths(List<ManifestRelativePath> roots) {
        return roots.stream().map(ManifestRelativePath::value).toList();
    }
}
