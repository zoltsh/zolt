package sh.zolt.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

final class ExperimentalFeaturesTest {
    @TempDir
    Path temporary;

    @Test
    void optInRequiresAnExplicitOne() {
        assertFalse(ExperimentalFeatures.enabled(Map.of()));
        assertFalse(ExperimentalFeatures.enabled(Map.of("ZOLT_EXPERIMENTAL", "0")));
        assertFalse(ExperimentalFeatures.enabled(Map.of("ZOLT_EXPERIMENTAL", "true")));
        assertTrue(ExperimentalFeatures.enabled(Map.of("ZOLT_EXPERIMENTAL", "1")));
    }

    @Test
    void cliRejectsKotlinInitBeforeCreatingTheProject() {
        StringWriter errors = new StringWriter();
        CommandLine cli = commandLine(errors);
        int result = cli.execute("init", "preview", "--language", "kotlin",
                "--directory", temporary.toString());

        assertEquals(1, result, errors.toString());
        assertTrue(errors.toString().contains("ZOLT_EXPERIMENTAL=1"), errors.toString());
        assertFalse(Files.exists(temporary.resolve("preview")));
    }

    @Test
    void helpAndExplicitOptInRemainAvailable() {
        assertDoesNotThrow(() -> check(false, "quarkus", "--help"));
        assertDoesNotThrow(() -> check(false, "init", "preview", "--language", "kotlin", "--help"));
        assertDoesNotThrow(() -> check(true, "quarkus", "plan"));
        assertDoesNotThrow(() -> check(true, "init", "preview", "--language", "kotlin"));
    }

    @Test
    void javaNativeAndSpringBootJvmStayEnabled() throws IOException {
        Path plain = project("plain", "");
        assertDoesNotThrow(() -> projectCheck(plain, "native"));
        Path spring = project("spring", """
                [package]
                mode = "spring-boot"
                """);
        assertDoesNotThrow(() -> projectCheck(spring, "build"));
        assertDoesNotThrow(() -> projectCheck(spring, "package"));
    }

    @Test
    void kotlinToolchainIsRejectedBeforeAnyOutputMutation() throws IOException {
        Path project = project("preview", """
                [toolchain.kotlin]
                version = "2.2.0"
                """);
        Path marker = project.resolve("target/classes/marker");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, "keep");

        assertBlocked(project, "build", "Kotlin/JVM");
        assertEquals("keep", Files.readString(marker));
        assertDoesNotThrow(() -> check(true, "build", "--directory", project.toString()));
    }

    @Test
    void kotlinInGenericMainAndIntegrationRootsCannotBypassTheGate() throws IOException {
        Path project = project("generic", """
                [build]
                sources = ["sources"]
                [test.integration]
                sources = ["integration"]
                """);
        source(project, "sources/Main.kt");
        assertBlocked(project, "build", "Kotlin/JVM");
        Files.delete(project.resolve("sources/Main.kt"));
        source(project, "integration/Test.kt");
        assertBlocked(project, "integration-test", "Kotlin/JVM");
        Files.delete(project.resolve("integration/Test.kt"));
        source(project, "src/test/java/Test.kt");
        assertBlocked(project, "test", "Kotlin/JVM");
    }

    @Test
    void groovyMainIsGatedWhileSpockTestsRemainAvailable() throws IOException {
        Path project = project("groovy", """
                [build]
                sources = ["sources"]
                [test.sources]
                groovy = ["src/test/groovy"]
                """);
        source(project, "src/test/groovy/DemoSpec.groovy");
        assertDoesNotThrow(() -> projectCheck(project, "test"));
        source(project, "sources/Main.groovy");
        assertBlocked(project, "build", "Groovy main");
    }

    @Test
    void generatedKotlinIsGatedBeforeRunningGenerators() throws IOException {
        Path project = project("generated", """
                [generated.main.model]
                kind = "declared-root"
                language = "kotlin"
                output = "generated/model"
                inputs = ["schema/model.json"]
                """);
        assertBlocked(project, "build", "Kotlin/JVM");
        assertFalse(Files.exists(project.resolve("generated")));
    }

    @Test
    void quarkusAndSpringAotCanariesRequireOptIn() throws IOException {
        assertThrows(CommandLine.ExecutionException.class, () -> check(false, "quarkus", "plan"));
        assertThrows(CommandLine.ExecutionException.class,
                () -> check(false, "package", "--mode", "quarkus"));
        Path quarkus = project("quarkus", """
                [package]
                mode = "quarkus"
                """);
        assertBlocked(quarkus, "package", "Quarkus");
        Path spring = project("spring-native", """
                [package]
                mode = "spring-boot"
                [framework.spring-boot]
                native = true
                """);
        assertBlocked(spring, "native", "Spring Boot AOT/native");
    }

    @Test
    void stableJsonErrorsKeepTheirEnvelope() throws IOException {
        Path project = project("json-preview", """
                [toolchain.kotlin]
                version = "2.2.0"
                """);
        StringWriter errors = new StringWriter();
        StringWriter output = new StringWriter();
        CommandLine cli = commandLine(errors);
        cli.setOut(new PrintWriter(output));

        assertEquals(1, cli.execute("check", "--format", "json", "--directory", project.toString()));
        assertTrue(output.toString().contains("\"schemaVersion\": 1"), output.toString());
        assertTrue(output.toString().contains("\"status\": \"failed\""), output.toString());
        assertTrue(errors.toString().isEmpty(), errors.toString());
    }

    @Test
    void workspaceSelectionKeepsUnselectedPreviewMembersOutOfJavaBuilds() throws IOException {
        Path workspace = workspace();
        projectAt(workspace.resolve("java"), "java", "");
        projectAt(workspace.resolve("preview"), "preview", """
                [toolchain.kotlin]
                version = "2.2.0"
                """);

        assertDoesNotThrow(() -> check(false, "build", "--workspace", "--directory", workspace.toString()));
        assertDoesNotThrow(() -> check(false, "build", "--directory", workspace.resolve("java").toString()));
        assertThrows(CommandLine.ExecutionException.class, () -> check(false,
                "build", "--workspace", "--members", "java,preview", "--directory", workspace.toString()));
        assertThrows(CommandLine.ExecutionException.class, () -> check(false,
                "resolve", "--workspace", "--directory", workspace.toString()));
    }

    @Test
    void workspaceDependencyClosureCannotHideAPreviewMember() throws IOException {
        Path workspace = workspace();
        projectAt(workspace.resolve("java"), "java", """
                [dependencies]
                "com.example:preview" = { workspace = true }
                """);
        projectAt(workspace.resolve("preview"), "preview", """
                [build]
                sources = ["sources"]
                """);
        source(workspace.resolve("preview"), "sources/Preview.kt");

        assertThrows(CommandLine.ExecutionException.class, () -> check(false,
                "build", "--workspace", "--member", "java", "--directory", workspace.toString()));
        assertThrows(CommandLine.ExecutionException.class, () -> check(false,
                "build", "--directory", workspace.resolve("java").toString()));
    }

    private Path workspace() throws IOException {
        Path workspace = temporary.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "demo"
                [workspace.members]
                include = ["java", "preview"]
                default = ["java"]
                """);
        return workspace;
    }

    private Path project(String name, String configuration) throws IOException {
        Path project = temporary.resolve(name);
        projectAt(project, name, configuration);
        return project;
    }

    private static void projectAt(Path project, String name, String configuration) throws IOException {
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "1.0.0"
                group = "com.example"
                java = 21
                %s
                """.formatted(name, configuration));
    }

    private static void source(Path project, String relative) throws IOException {
        Path source = project.resolve(relative);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "// preview source\n");
    }

    private static void assertBlocked(Path project, String command, String feature) {
        CommandLine.ExecutionException exception = assertThrows(CommandLine.ExecutionException.class,
                () -> projectCheck(project, command));
        assertTrue(exception.getMessage().contains(feature), exception.getMessage());
        assertTrue(exception.getMessage().contains("ZOLT_EXPERIMENTAL=1"), exception.getMessage());
    }

    private static void projectCheck(Path project, String command) {
        check(false, command, "--directory", project.toString());
    }

    private static void check(boolean experimental, String... arguments) {
        CommandLine cli = commandLine(new StringWriter());
        ExperimentalFeatures.check(cli.parseArgs(arguments), experimental);
    }

    private static CommandLine commandLine(StringWriter errors) {
        CommandLine cli = ZoltCli.newCommandLine(false);
        cli.setErr(new PrintWriter(errors));
        cli.setOut(new PrintWriter(new StringWriter()));
        return cli;
    }
}
