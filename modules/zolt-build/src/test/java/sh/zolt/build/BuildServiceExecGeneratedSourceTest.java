package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.BuildSettings;
import sh.zolt.project.ExecGenerationSettings;
import sh.zolt.project.ExecToolCoordinate;
import sh.zolt.project.ExecToolSettings;
import sh.zolt.project.GeneratedSourceKind;
import sh.zolt.project.GeneratedSourceStep;
import sh.zolt.project.OpenApiGenerationSettings;
import sh.zolt.project.ProducesLane;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.project.ProtobufGenerationSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BuildServiceExecGeneratedSourceTest {
    private final BuildService buildService = new BuildService();

    @TempDir
    private Path projectDir;

    @Test
    void execGeneratedJavaSourcesCompileIntoTheBuild() throws IOException {
        seedToolAndLock();
        source("src/main/gen/config.txt", "generate\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                    public static String message() {
                        return com.example.generated.GeneratedMessage.value();
                    }
                }
                """);
        GeneratedSourceStep step = execStep(
                "model",
                "target/generated/sources/gen",
                ProducesLane.JAVA_SOURCES,
                Optional.empty(),
                List.of(
                        "com/example/generated/GeneratedMessage.java",
                        "package com.example.generated; public final class GeneratedMessage "
                                + "{ public static String value() { return \"generated\"; } }"));
        ProjectConfig config = configWith(step);

        BuildResult result = buildService.build(projectDir, config, projectDir.resolve("cache"));

        assertEquals(2, result.sourceCount());
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/Main.class")));
        assertTrue(Files.exists(projectDir.resolve("target/classes/com/example/generated/GeneratedMessage.class")));
    }

    @Test
    void execResourcesAreCopiedUnderIntoSubtree() throws IOException {
        seedToolAndLock();
        source("src/main/gen/config.txt", "generate\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                }
                """);
        GeneratedSourceStep step = execStep(
                "assets",
                "target/generated/resources/gen",
                ProducesLane.RESOURCES,
                Optional.of("config"),
                List.of("app.properties", "generated=true"));
        ProjectConfig config = configWith(step);

        buildService.build(projectDir, config, projectDir.resolve("cache"));

        Path copied = projectDir.resolve("target/classes/config/app.properties");
        assertTrue(Files.exists(copied));
        assertEquals("generated=true", Files.readString(copied));
    }

    @Test
    void execOutputByteChangeInvalidatesMainBuildFingerprint() throws IOException {
        seedToolAndLock();
        source("src/main/gen/config.txt", "generate\n");
        source("src/main/java/com/example/Main.java", """
                package com.example;

                public final class Main {
                }
                """);
        GeneratedSourceStep step = execStep(
                "assets",
                "target/generated/resources/gen",
                ProducesLane.RESOURCES,
                Optional.of("config"),
                List.of("app.properties", "generated=true"));
        ProjectConfig config = configWith(step);

        buildService.build(projectDir, config, projectDir.resolve("cache"));
        BuildResult unchanged = buildService.build(projectDir, config, projectDir.resolve("cache"));
        assertTrue(unchanged.mainCompilationSkipped());

        // change the exec output bytes only; inputs are unchanged so the producer cache skips re-running,
        // but the consumer fence must still invalidate the module fingerprint.
        Files.writeString(projectDir.resolve("target/generated/resources/gen/app.properties"), "generated=changed");
        BuildResult afterOutputChange = buildService.build(projectDir, config, projectDir.resolve("cache"));

        assertFalse(afterOutputChange.mainCompilationSkipped());
    }

    @Test
    void projectPseudoToolRunsAfterCompileAndItsResourceIsCopied() throws IOException {
        writeLockfile("version = 1\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        source("src/main/java/com/example/Gen.java", """
                package com.example;

                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class Gen {
                    public static void main(String[] args) throws Exception {
                        Path out = Path.of(System.getenv("ZOLT_OUTPUT_DIR")).resolve("generated.properties");
                        Files.createDirectories(out.getParent());
                        Files.writeString(out, "from=project-classes\\n");
                    }
                }
                """);
        ProjectConfig config = configWith(projectResourceStep(ProducesLane.RESOURCES));

        buildService.build(projectDir, config, projectDir.resolve("cache"));

        Path copied = projectDir.resolve("target/classes/config/generated.properties");
        assertTrue(Files.exists(copied), "post-compile project resource must be copied into the package output");
        assertEquals("from=project-classes\n", Files.readString(copied));
    }

    @Test
    void projectStepProducingSourcesIsBlockedBeforeCompile() throws IOException {
        writeLockfile("version = 1\n");
        source("src/main/java/com/example/Main.java", "package com.example; public final class Main {}\n");
        ProjectConfig config = configWith(projectResourceStep(ProducesLane.JAVA_SOURCES));

        RuntimeException exception = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> buildService.build(projectDir, config, projectDir.resolve("cache")));

        assertTrue(exception.getMessage().contains("Post-compile steps may only produce"), exception.getMessage());
    }

    private static GeneratedSourceStep projectResourceStep(ProducesLane produces) {
        ExecGenerationSettings exec = new ExecGenerationSettings(
                "project",
                ExecToolSettings.project("com.example.Gen"),
                List.of(),
                produces,
                produces == ProducesLane.RESOURCES ? Optional.of("config") : Optional.empty(),
                Map.of(),
                "content");
        return new GeneratedSourceStep(
                "gen",
                GeneratedSourceKind.EXEC,
                "java",
                "target/generated/resources/gen",
                List.of("target/classes"),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                exec);
    }

    private void seedToolAndLock() throws IOException {
        Path jar = ExecToolJarFixture.generatorJar(projectDir.resolve("fixture-work"));
        Path cachedJar = projectDir.resolve("cache/com/example/gen-tool/1.0.0/gen-tool-1.0.0.jar");
        Files.createDirectories(cachedJar.getParent());
        Files.copy(jar, cachedJar);
        writeLockfile("""
                version = 1

                [[package]]
                id = "com.example:gen-tool"
                version = "1.0.0"
                source = "maven-central"
                scope = "tool-exec"
                direct = true
                jar = "com/example/gen-tool/1.0.0/gen-tool-1.0.0.jar"
                toolGroups = ["gen-tool"]
                dependencies = []
                """);
    }

    private ProjectConfig configWith(GeneratedSourceStep step) {
        return config().withBuildSettings(config().build().withGeneratedSources(List.of(step), List.of()));
    }

    private static GeneratedSourceStep execStep(
            String id,
            String output,
            ProducesLane produces,
            Optional<String> into,
            List<String> args) {
        ExecGenerationSettings exec = new ExecGenerationSettings(
                "gen-tool",
                new ExecToolSettings(
                        "jvm",
                        List.of(new ExecToolCoordinate("com.example:gen-tool", Optional.of("1.0.0"), Optional.empty())),
                        ExecToolJarFixture.MAIN_CLASS),
                args,
                produces,
                into,
                Map.of(),
                "content");
        return new GeneratedSourceStep(
                id,
                GeneratedSourceKind.EXEC,
                "java",
                output,
                List.of("src/main/gen/config.txt"),
                true,
                true,
                OpenApiGenerationSettings.empty(),
                ProtobufGenerationSettings.empty(),
                exec);
    }

    private static ProjectConfig config() {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(
                        "demo",
                        "0.1.0",
                        "com.example",
                        currentJavaMajorVersion(),
                        Optional.of("com.example.Main")),
                ProjectConfig.defaultRepositories(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private Path source(String path, String content) throws IOException {
        Path source = projectDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
        return source;
    }

    private void writeLockfile(String content) throws IOException {
        sh.zolt.build.lockfile.ContentAddressedLockTestSupport.write(
                projectDir.resolve("zolt.lock"), projectDir.resolve("cache"), content);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }
}
