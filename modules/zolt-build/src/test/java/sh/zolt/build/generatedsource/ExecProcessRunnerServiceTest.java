package sh.zolt.build.generatedsource;

import static sh.zolt.build.generatedsource.ExecProcessRunnerTestSupport.service;
import static sh.zolt.build.generatedsource.ExecProcessRunnerTestSupport.writeScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.project.ProjectConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ExecProcessRunnerServiceTest {
    @TempDir
    private Path projectDir;

    @TempDir
    private Path binDir;

    @Test
    void runsDiscoveredBinaryAndRerunsWhenProbedVersionChanges() throws IOException {
        seedInput();
        writeScript(binDir, "zoltprobe", "echo 1.0.0");
        writeScript(binDir, "zoltgen", "echo run >> \"$ZOLT_OUTPUT_DIR/log.txt\"");
        ExecGeneratedSourceService service = service(projectDir, binDir, Map.of());
        ProjectConfig config = config(processTool("allowUnpinnedTool = true"), "clean = false");

        service.generateMain(projectDir, config, List.of());
        service.generateMain(projectDir, config, List.of());
        assertEquals(1, runCount(), "unchanged probe should let the content cache skip the second run");

        writeScript(binDir, "zoltprobe", "echo 2.0.0");
        service.generateMain(projectDir, config, List.of());
        assertEquals(2, runCount(), "a changed probe version must invalidate the fingerprint and re-run");
    }

    @Test
    void versionExpectSatisfiedRunsTheStep() throws IOException {
        seedInput();
        writeScript(binDir, "zoltprobe", "echo 1.4.2");
        writeScript(binDir, "zoltgen", "echo built > \"$ZOLT_OUTPUT_DIR/app.txt\"");
        service(projectDir, binDir, Map.of())
                .generateMain(projectDir, config(processTool("allowUnpinnedTool = true\nversionExpect = \">=1 <2\""), ""), List.of());
        assertTrue(Files.exists(projectDir.resolve("target/generated/assets/app.txt")));
    }

    @Test
    void versionExpectViolationFailsFastBeforeRunning() throws IOException {
        seedInput();
        writeScript(binDir, "zoltprobe", "echo 1.4.2");
        writeScript(binDir, "zoltgen", "echo built > \"$ZOLT_OUTPUT_DIR/app.txt\"");
        ProjectConfig config = config(processTool("allowUnpinnedTool = true\nversionExpect = \">=2\""), "");

        BuildException exception = assertThrows(
                BuildException.class, () -> service(projectDir, binDir, Map.of()).generateMain(projectDir, config, List.of()));

        assertTrue(exception.getMessage().contains("versionExpect"), exception.getMessage());
        assertTrue(exception.getMessage().contains("1.4.2"), exception.getMessage());
        assertFalse(Files.exists(projectDir.resolve("target/generated/assets/app.txt")));
    }

    @Test
    void unpinnedProcessToolRequiresExplicitAcknowledgement() throws IOException {
        seedInput();
        ProjectConfig config = config(processTool(""), "");

        BuildException exception = assertThrows(
                BuildException.class, () -> service(projectDir, binDir, Map.of()).generateMain(projectDir, config, List.of()));

        assertTrue(exception.getMessage().contains("allowUnpinnedTool"), exception.getMessage());
    }

    @Test
    void secretEnvIsInjectedButItsValueNeverReachesFingerprintOrLog() throws IOException {
        seedInput();
        writeScript(binDir, "zoltprobe", "echo 1.0.0");
        writeScript(binDir, "zoltgen", "if [ -n \"$TOKEN\" ]; then echo present > \"$ZOLT_OUTPUT_DIR/marker\"; fi");
        ProjectConfig config = config(
                processTool("allowUnpinnedTool = true"),
                "cacheSalt = \"s1\"\n[generated.main.build-assets.secretEnv]\nTOKEN = \"CI_TOKEN\"");

        service(projectDir, binDir, Map.of("CI_TOKEN", "s3cr3t-value-xyz")).generateMain(projectDir, config, List.of());

        assertTrue(Files.exists(projectDir.resolve("target/generated/assets/marker")), "secret should be injected");
        Path fingerprint = projectDir.resolve("target/.zolt/exec/exec-main-build-assets.fingerprint");
        Path log = projectDir.resolve("target/.zolt/exec/exec-main-build-assets.log");
        assertFalse(Files.readString(fingerprint).contains("s3cr3t-value-xyz"), "value must not reach the fingerprint");
        assertFalse(Files.readString(log).contains("s3cr3t-value-xyz"), "value must not reach the log");
    }

    @Test
    void secretEnvUnsetSourceFailsNamingTheVariable() throws IOException {
        seedInput();
        writeScript(binDir, "zoltprobe", "echo 1.0.0");
        writeScript(binDir, "zoltgen", "echo built > \"$ZOLT_OUTPUT_DIR/app.txt\"");
        ProjectConfig config = config(
                processTool("allowUnpinnedTool = true"),
                "cacheSalt = \"s1\"\n[generated.main.build-assets.secretEnv]\nTOKEN = \"MISSING_TOKEN\"");

        BuildException exception = assertThrows(
                BuildException.class, () -> service(projectDir, binDir, Map.of()).generateMain(projectDir, config, List.of()));

        assertTrue(exception.getMessage().contains("MISSING_TOKEN"), exception.getMessage());
        assertFalse(Files.exists(projectDir.resolve("target/generated/assets/app.txt")));
    }

    @Test
    void inheritEnvPassesThroughOnlyAllowlistedAmbientVariables() throws IOException {
        seedInput();
        writeScript(binDir, "zoltprobe", "echo 1.0.0");
        writeScript(binDir, "zoltgen",
                "if [ -n \"$MY_FLAG\" ]; then echo yes > \"$ZOLT_OUTPUT_DIR/flag\"; fi\n"
                        + "if [ -n \"$OTHER\" ]; then echo yes > \"$ZOLT_OUTPUT_DIR/leak\"; fi");
        ProjectConfig config = config(processTool("allowUnpinnedTool = true"), "inheritEnv = [\"MY_FLAG\"]");

        service(projectDir, binDir, Map.of("MY_FLAG", "on", "OTHER", "leak"))
                .generateMain(projectDir, config, List.of());

        assertTrue(Files.exists(projectDir.resolve("target/generated/assets/flag")), "allowlisted var must pass through");
        assertFalse(Files.exists(projectDir.resolve("target/generated/assets/leak")), "non-allowlisted var must not");
    }

    @Test
    void timeoutTerminatesHangingStepWithActionableError() throws IOException {
        seedInput();
        writeScript(binDir, "zoltprobe", "echo 1.0.0");
        writeScript(binDir, "zoltgen", """
                echo $$ > "$ZOLT_PROJECT_ROOT/parent.pid"
                (
                  trap '' TERM
                  sleep 30 &
                  echo $! > "$ZOLT_PROJECT_ROOT/grandchild.pid"
                  wait
                ) &
                echo $! > "$ZOLT_PROJECT_ROOT/child.pid"
                while [ ! -s "$ZOLT_PROJECT_ROOT/grandchild.pid" ]; do :; done
                wait
                """);
        ProjectConfig config = config(processTool("allowUnpinnedTool = true"), "timeoutSeconds = 1");

        BuildException exception = assertThrows(
                BuildException.class, () -> service(projectDir, binDir, Map.of()).generateMain(projectDir, config, List.of()));

        assertTrue(exception.getMessage().contains("terminated"), exception.getMessage());
        assertTrue(exception.getMessage().contains("1s"), exception.getMessage());
        assertTrue(exception.getMessage().contains("exec-main-build-assets.log"), exception.getMessage());
        assertStopped("parent.pid", "child.pid", "grandchild.pid");
    }

    @Test
    void undeclaredOutputWrittenIntoCwdFailsTheCheck() throws IOException {
        seedInput();
        writeScript(binDir, "zoltprobe", "echo 1.0.0");
        writeScript(binDir, "zoltgen",
                "echo built > \"$ZOLT_OUTPUT_DIR/app.txt\"\necho rogue > \"$ZOLT_PROJECT_ROOT/rogue.txt\"");
        ProjectConfig config = config(processTool("allowUnpinnedTool = true"), "");

        BuildException exception = assertThrows(
                BuildException.class, () -> service(projectDir, binDir, Map.of()).generateMain(projectDir, config, List.of()));

        assertTrue(exception.getMessage().contains("outside its declared output"), exception.getMessage());
        assertTrue(exception.getMessage().contains("rogue.txt"), exception.getMessage());
    }

    @Test
    void runsInDeclaredWorkingDirectory() throws IOException {
        seedInput();
        Files.createDirectories(projectDir.resolve("web"));
        writeScript(binDir, "zoltprobe", "echo 1.0.0");
        writeScript(binDir, "zoltgen", "pwd > \"$ZOLT_OUTPUT_DIR/cwd.txt\"");
        ProjectConfig config = config(processTool("allowUnpinnedTool = true"), "cwd = \"web\"");

        service(projectDir, binDir, Map.of()).generateMain(projectDir, config, List.of());

        String cwd = Files.readString(projectDir.resolve("target/generated/assets/cwd.txt")).strip();
        assertTrue(cwd.endsWith("/web") || cwd.contains("/web"), cwd);
    }

    private void seedInput() throws IOException {
        Files.writeString(projectDir.resolve("seed.txt"), "seed\n");
    }

    private int runCount() throws IOException {
        Path log = projectDir.resolve("target/generated/assets/log.txt");
        return Files.exists(log) ? (int) Files.readString(log).lines().count() : 0;
    }

    private void assertStopped(String... pidFiles) {
        for (String pidFile : pidFiles) {
            try {
                long pid = Long.parseLong(Files.readString(projectDir.resolve(pidFile)).strip());
                ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
                if (handle != null && handle.isAlive()) {
                    handle.onExit().get(5, TimeUnit.SECONDS);
                }
                assertTrue(
                        ProcessHandle.of(pid).map(process -> !process.isAlive()).orElse(true),
                        "Timed-out generated tool descendant is still alive: " + pid);
            } catch (Exception exception) {
                throw new AssertionError("Could not verify stopped process from " + pidFile, exception);
            }
        }
    }

    private static String processTool(String toolExtras) {
        return """
                [generated.execTools.gen]
                runner = "process"
                binary = "zoltgen"
                versionCommand = ["zoltprobe"]
                %s""".formatted(toolExtras);
    }

    private static ProjectConfig config(String toolBlock, String stepExtras) {
        return ExecProcessRunnerTestSupport.config("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                %s

                [generated.main.build-assets]
                kind = "exec"
                tool = "gen"
                inputs = ["seed.txt"]
                output = "target/generated/assets"
                produces = "resources"
                %s
                """.formatted(toolBlock, stepExtras));
    }
}
