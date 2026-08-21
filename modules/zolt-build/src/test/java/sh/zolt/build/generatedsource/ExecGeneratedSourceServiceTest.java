package sh.zolt.build.generatedsource;

import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.config;
import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.generatingRunner;
import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.packages;
import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.service;
import static sh.zolt.build.generatedsource.ExecGeneratedSourceServiceTestSupport.writeProjectFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExecGeneratedSourceServiceTest {
    private static final Set<String> ALLOWED_ENV_NAMES = Set.of(
            "PATH", "HOME", "ZOLT_PROJECT_ROOT", "ZOLT_STEP_ID", "ZOLT_STEP_SCOPE", "ZOLT_OUTPUT_DIR");

    @TempDir
    private Path projectDir;

    @Test
    void runsExecToolKeepsToolingOffAppClasspathAndWritesSidecarsOutsideOutput() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        service(projectDir, generatingRunner(commands)).generateMain(projectDir, config(), packages(projectDir));

        assertEquals(1, commands.size());
        List<String> command = commands.getFirst();
        assertEquals(projectDir.resolve("fake-java").toString(), command.getFirst());
        assertTrue(command.contains("com.example.GenerationTool"));
        String classpath = command.get(command.indexOf("-cp") + 1);
        assertTrue(classpath.contains("jooq-codegen-3.19.15.jar"));
        assertFalse(classpath.contains("app-1.0.0.jar"));
        assertTrue(command.contains("src/main/jooq/config.xml"));

        Path output = projectDir.resolve("target/generated/sources/jooq");
        assertTrue(Files.exists(output.resolve("com/example/generated/Model.java")));
        assertTrue(Files.exists(projectDir.resolve("target/.zolt/exec/exec-main-model.fingerprint")));
        assertTrue(Files.exists(projectDir.resolve("target/.zolt/exec/exec-main-model.log")));
        // sidecars must NOT live inside the output dir (they would poison the consumer fence / leak into resources)
        assertFalse(Files.exists(output.resolve("exec-main-model.fingerprint")));
        assertFalse(Files.exists(output.resolve(".zolt-exec-main-model.fingerprint")));
    }

    @Test
    void skipsWhenFingerprintIsCurrent() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        ExecGeneratedSourceService service = service(projectDir, generatingRunner(commands));

        service.generateMain(projectDir, config(), packages(projectDir));
        service.generateMain(projectDir, config(), packages(projectDir));

        assertEquals(1, commands.size());
    }

    @Test
    void regeneratesWhenInputChanges() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        ExecGeneratedSourceService service = service(projectDir, generatingRunner(commands));

        service.generateMain(projectDir, config(), packages(projectDir));
        Files.writeString(projectDir.resolve("src/main/jooq/config.xml"), "<configuration changed=\"true\"/>\n");
        service.generateMain(projectDir, config(), packages(projectDir));

        assertEquals(2, commands.size());
    }

    @Test
    void regeneratesWhenArgsChange() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        ExecGeneratedSourceService service = service(projectDir, generatingRunner(commands));

        service.generateMain(projectDir, config("args = [\"src/main/jooq/config.xml\"]"), packages(projectDir));
        service.generateMain(
                projectDir, config("args = [\"src/main/jooq/config.xml\", \"--verbose\"]"), packages(projectDir));

        assertEquals(2, commands.size());
    }

    @Test
    void regeneratesWhenToolJarBytesChange() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        ExecGeneratedSourceService service = service(projectDir, generatingRunner(commands));

        service.generateMain(projectDir, config(), packages(projectDir));
        Files.writeString(
                projectDir.resolve("cache/org/jooq/jooq-codegen/3.19.15/jooq-codegen-3.19.15.jar"), "tool-v2\n");
        service.generateMain(projectDir, config(), packages(projectDir));

        assertEquals(2, commands.size());
    }

    @Test
    void buildsCuratedEnvironmentWithoutAmbientInheritance() throws IOException {
        writeProjectFiles(projectDir);
        List<java.util.Map<String, String>> environments = new ArrayList<>();
        ExecGeneratedSourceService service = service(projectDir, (command, directory, environment, timeout) -> {
            environments.add(environment);
            try {
                Files.createDirectories(Path.of(environment.get("ZOLT_OUTPUT_DIR")));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new ExecGeneratedSourceService.ProcessResult(0, "ok\n", false);
        });

        service.generateMain(projectDir, config("env = { BUILD_MODE = \"release\" }"), packages(projectDir));

        java.util.Map<String, String> environment = environments.getFirst();
        assertEquals(projectDir.toAbsolutePath().normalize().toString(), environment.get("ZOLT_PROJECT_ROOT"));
        assertEquals("model", environment.get("ZOLT_STEP_ID"));
        assertEquals("main", environment.get("ZOLT_STEP_SCOPE"));
        assertEquals("release", environment.get("BUILD_MODE"));
        assertTrue(environment.containsKey("ZOLT_OUTPUT_DIR"));
        for (String name : environment.keySet()) {
            assertTrue(ALLOWED_ENV_NAMES.contains(name) || name.equals("BUILD_MODE"), "unexpected inherited env var: " + name);
        }
    }

    @Test
    void failsWithActionableErrorAndLogPathOnNonZeroExit() throws IOException {
        writeProjectFiles(projectDir);
        ExecGeneratedSourceService service = service(projectDir, (command, directory, environment, timeout) -> {
            try {
                Files.createDirectories(Path.of(environment.get("ZOLT_OUTPUT_DIR")));
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new ExecGeneratedSourceService.ProcessResult(2, "tool exploded\n", false);
        });

        BuildException exception = assertThrows(
                BuildException.class, () -> service.generateMain(projectDir, config(), packages(projectDir)));

        assertTrue(exception.getMessage().contains("exit code 2"), exception.getMessage());
        assertTrue(exception.getMessage().contains("exec-main-model.log"), exception.getMessage());
        assertTrue(exception.getMessage().contains("tool exploded"), exception.getMessage());
    }

    @Test
    void schedulesStepsFromDeclaredInputOutputEdges() throws IOException {
        writeProjectFiles(projectDir);
        Files.createDirectories(projectDir.resolve("src/main/seed"));
        Files.writeString(projectDir.resolve("src/main/seed/seed.txt"), "seed\n");
        List<String> stepOrder = new ArrayList<>();
        ExecGeneratedSourceService service = service(projectDir, (command, directory, environment, timeout) -> {
            stepOrder.add(environment.get("ZOLT_STEP_ID"));
            try {
                Path output = Path.of(environment.get("ZOLT_OUTPUT_DIR"));
                Files.createDirectories(output);
                Files.writeString(output.resolve("out.txt"), "produced\n");
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new ExecGeneratedSourceService.ProcessResult(0, "ok\n", false);
        });

        service.generateMain(projectDir, twoStepChainConfig(), packages(projectDir, "t"));

        assertEquals(List.of("gen-a", "gen-b"), stepOrder);
    }

    @Test
    void rejectsMissingLiteralInput() throws IOException {
        writeProjectFiles(projectDir);
        Files.delete(projectDir.resolve("src/main/jooq/config.xml"));

        BuildException exception = assertThrows(
                BuildException.class,
                () -> service(projectDir, generatingRunner(new ArrayList<>()))
                        .generateMain(projectDir, config(), packages(projectDir)));

        assertTrue(exception.getMessage().contains("does not exist"), exception.getMessage());
    }

    @Test
    void cacheNoneAlwaysRunsAndIsRejectedUnderOffline() throws IOException {
        writeProjectFiles(projectDir);
        List<List<String>> commands = new ArrayList<>();
        ExecGeneratedSourceService service = service(projectDir, generatingRunner(commands));
        var config = config("args = [\"src/main/jooq/config.xml\"]\ncache = \"none\"");

        service.generateMain(projectDir, config, packages(projectDir));
        service.generateMain(projectDir, config, packages(projectDir));
        assertEquals(2, commands.size(), "cache = \"none\" must always run, never skip");

        BuildException offline = assertThrows(
                BuildException.class, () -> service.generateMain(projectDir, config, packages(projectDir), true));
        assertTrue(offline.getMessage().contains("--offline"), offline.getMessage());
    }

    @Test
    void intermediateLaneStepChainsIntoConsumerInDerivedOrder() throws IOException {
        writeProjectFiles(projectDir);
        Files.createDirectories(projectDir.resolve("web"));
        Files.writeString(projectDir.resolve("web/package.json"), "{}\n");
        List<String> order = new ArrayList<>();
        ExecGeneratedSourceService service = service(projectDir, (command, directory, environment, timeout) -> {
            order.add(environment.get("ZOLT_STEP_ID"));
            try {
                Path output = Path.of(environment.get("ZOLT_OUTPUT_DIR"));
                Files.createDirectories(output);
                Files.writeString(output.resolve("out.txt"), "x\n");
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new ExecGeneratedSourceService.ProcessResult(0, "ok\n", false);
        });

        service.generateMain(projectDir, npmChainConfig(), packages(projectDir, "t"));

        assertEquals(List.of("install", "build"), order);
    }

    @Test
    void postCompileProjectStepProducingSourcesIsBlocked() throws IOException {
        writeProjectFiles(projectDir);

        BuildException exception = assertThrows(
                BuildException.class,
                () -> service(projectDir, generatingRunner(new ArrayList<>()))
                        .generateMain(projectDir, projectSourcesConfig(), packages(projectDir)));

        assertTrue(exception.getMessage().contains("Post-compile steps may only produce"), exception.getMessage());
    }

    private static sh.zolt.project.ProjectConfig npmChainConfig() {
        return new sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                jooq = "3.19.15"

                [generated.tools.t]
                kind = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" }]
                mainClass = "com.example.GenerationTool"

                [generated.main.build]
                kind = "exec"
                tool = "t"
                inputs = ["web/node_modules"]
                output = "web/dist"
                produces = "resources"

                [generated.main.install]
                kind = "exec"
                tool = "t"
                inputs = ["web/package.json"]
                output = "web/node_modules"
                produces = "intermediate"
                """);
    }

    private static sh.zolt.project.ProjectConfig projectSourcesConfig() {
        return new sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [generated.main.gen]
                kind = "exec"
                tool = "project"
                mainClass = "com.example.Gen"
                inputs = ["target/classes"]
                output = "target/generated/sources/gen"
                produces = "java-sources"
                """);
    }

    private static sh.zolt.project.ProjectConfig twoStepChainConfig() {
        return new sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                jooq = "3.19.15"

                [generated.tools.t]
                kind = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" }]
                mainClass = "com.example.GenerationTool"

                [generated.main.gen-b]
                kind = "exec"
                tool = "t"
                inputs = ["target/generated/a/out.txt"]
                output = "target/generated/b"
                produces = "java-sources"

                [generated.main.gen-a]
                kind = "exec"
                tool = "t"
                inputs = ["src/main/seed/seed.txt"]
                output = "target/generated/a"
                produces = "java-sources"
                """);
    }
}
