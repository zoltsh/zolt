package sh.zolt.build.coverage;

import static sh.zolt.build.TestContentAddressedLockSupport.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.run.JavaRunResult;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.test.runtime.TestJvmArguments;
import sh.zolt.build.testruntime.TestReportSettings;
import sh.zolt.build.testruntime.TestRunResult;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.test.shard.TestShardSpec;
import sh.zolt.test.TestSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CoverageServiceTest {
    @TempDir
    private Path projectDir;

    @Test
    void runsTestsWithJacocoAgentAndGeneratesXmlAndHtmlReports() throws IOException {
        writeCoverageLockfile();
        List<TestJvmArguments> testJvmArguments = new ArrayList<>();
        List<TestReportSettings> testReportSettings = new ArrayList<>();
        List<List<String>> reportCommands = new ArrayList<>();
        CoverageService service = service(
                (projectDirectory, config, cacheRoot, selection, jvmArguments, reportSettings, cliEvents, suiteName, shard) -> {
                    testJvmArguments.add(jvmArguments);
                    testReportSettings.add(reportSettings);
                    return new TestRunResult(null, "Tests passed\n", "junit-console", 3, 1, 1);
                },
                reportCommands);

        CoverageResult result = service.runCoverage(
                projectDir,
                config(),
                projectDir.resolve("cache"),
                TestSelection.empty(),
                CoverageReportSettings.defaults(),
                List.of("failed"));

        Path execFile = projectDir.resolve("target/coverage/jacoco.exec").toAbsolutePath().normalize();
        assertEquals(execFile, result.execFile());
        assertEquals(Optional.of(projectDir.resolve("target/coverage/jacoco.xml").toAbsolutePath().normalize()), result.xmlReport());
        assertEquals(Optional.of(projectDir.resolve("target/coverage/html").toAbsolutePath().normalize()), result.htmlDirectory());
        assertEquals("Tests passed\n", result.testRunResult().output());
        assertTrue(testJvmArguments.getFirst().values().getFirst().startsWith("-javaagent:"));
        assertTrue(testJvmArguments.getFirst().values().getFirst().contains("org.jacoco.agent-0.8.14-runtime.jar"));
        assertTrue(testJvmArguments.getFirst().values().getFirst().contains("destfile=" + execFile));
        assertEquals(Optional.of(Path.of("target/coverage/test-reports")), testReportSettings.getFirst().reportsDirectory());

        List<String> command = reportCommands.getFirst();
        assertTrue(command.contains("org.jacoco.cli.internal.Main"));
        assertTrue(command.contains("report"));
        assertTrue(command.contains(execFile.toString()));
        assertEquals(projectDir.resolve("target/classes").toAbsolutePath().normalize().toString(), argumentAfter(command, "--classfiles"));
        assertEquals(projectDir.resolve("src/main/java").toAbsolutePath().normalize().toString(), argumentAfter(command, "--sourcefiles"));
        assertEquals(projectDir.resolve("target/coverage/jacoco.xml").toAbsolutePath().normalize().toString(), argumentAfter(command, "--xml"));
        assertEquals(projectDir.resolve("target/coverage/html").toAbsolutePath().normalize().toString(), argumentAfter(command, "--html"));
        assertTrue(command.stream().anyMatch(value -> value.contains("org.jacoco.cli-0.8.14.jar")));
    }

    @Test
    void canDisableHtmlReport() throws IOException {
        writeCoverageLockfile();
        List<List<String>> reportCommands = new ArrayList<>();
        CoverageService service = service(
                (projectDirectory, config, cacheRoot, selection, jvmArguments, reportSettings, cliEvents, suiteName, shard) ->
                        new TestRunResult(null, "Tests passed\n"),
                reportCommands);

        CoverageResult result = service.runCoverage(
                projectDir,
                config(),
                projectDir.resolve("cache"),
                TestSelection.empty(),
                new CoverageReportSettings(
                        true,
                        false,
                        Path.of("target/coverage/jacoco.exec"),
                        Path.of("target/coverage/jacoco.xml"),
                        Path.of("target/coverage/html"),
                        TestReportSettings.reportsDirectory(Path.of("target/coverage/test-reports"))),
                List.of());

        assertTrue(result.htmlDirectory().isEmpty());
        assertTrue(reportCommands.getFirst().contains("--xml"));
        assertTrue(!reportCommands.getFirst().contains("--html"));
    }

    @Test
    void mergesSplitWorkerExecutionDataBeforeGeneratingReports() throws IOException {
        writeCoverageLockfile();
        Path coverageRoot = projectDir.resolve("target/coverage");
        Files.createDirectories(coverageRoot.resolve("workers/wave-1-worker-1"));
        Files.createDirectories(coverageRoot.resolve("workers/wave-1-worker-2"));
        Files.writeString(coverageRoot.resolve("workers/wave-1-worker-1/jacoco.exec"), "worker-one\n");
        Files.writeString(coverageRoot.resolve("workers/wave-1-worker-2/jacoco.exec"), "worker-two\n");
        List<List<String>> reportCommands = new ArrayList<>();
        CoverageService service = service(
                (projectDirectory, config, cacheRoot, selection, jvmArguments, reportSettings, cliEvents, suiteName, shard) ->
                        new TestRunResult(null, "Tests passed\n"),
                reportCommands);

        Path execFile = projectDir.resolve("target/coverage/jacoco.exec").toAbsolutePath().normalize();
        service.runCoverage(
                projectDir,
                config(),
                projectDir.resolve("cache"),
                TestSelection.empty(),
                CoverageReportSettings.defaults(),
                List.of());

        assertEquals(2, reportCommands.size());
        List<String> mergeCommand = reportCommands.getFirst();
        assertTrue(mergeCommand.contains("merge"));
        assertTrue(mergeCommand.contains(projectDir.resolve("target/coverage/workers/wave-1-worker-1/jacoco.exec").toAbsolutePath().normalize().toString()));
        assertTrue(mergeCommand.contains(projectDir.resolve("target/coverage/workers/wave-1-worker-2/jacoco.exec").toAbsolutePath().normalize().toString()));
        assertEquals(execFile.toString(), argumentAfter(mergeCommand, "--destfile"));
        List<String> reportCommand = reportCommands.get(1);
        assertTrue(reportCommand.contains("report"));
        assertTrue(reportCommand.contains(execFile.toString()));
    }

    @Test
    void shardCoverageUsesShardSpecificOutputPaths() throws IOException {
        writeCoverageLockfile();
        List<String> receivedSuites = new ArrayList<>();
        List<TestShardSpec> receivedShards = new ArrayList<>();
        List<TestJvmArguments> testJvmArguments = new ArrayList<>();
        List<List<String>> reportCommands = new ArrayList<>();
        CoverageService service = service(
                (projectDirectory, config, cacheRoot, selection, jvmArguments, reportSettings, cliEvents, suiteName, shard) -> {
                    receivedSuites.add(suiteName);
                    receivedShards.add(shard);
                    testJvmArguments.add(jvmArguments);
                    return new TestRunResult(null, "Tests passed\n");
                },
                reportCommands);

        CoverageResult result = service.runCoverage(
                projectDir,
                config(),
                projectDir.resolve("cache"),
                TestSelection.empty(),
                CoverageReportSettings.defaults(),
                List.of(),
                "fast",
                new TestShardSpec(2, 4));

        Path shardRoot = projectDir.resolve("target/coverage/shards/fast/shard-2-of-4").toAbsolutePath().normalize();
        assertEquals(shardRoot.resolve("jacoco.exec"), result.execFile());
        assertEquals(Optional.of(shardRoot.resolve("jacoco.xml")), result.xmlReport());
        assertEquals(Optional.of(shardRoot.resolve("html")), result.htmlDirectory());
        assertEquals(List.of("fast"), receivedSuites);
        assertEquals(List.of(new TestShardSpec(2, 4)), receivedShards);
        assertTrue(testJvmArguments.getFirst().values().getFirst().contains("destfile=" + shardRoot.resolve("jacoco.exec")));
        List<String> command = reportCommands.getFirst();
        assertTrue(command.contains("report"));
        assertTrue(command.contains(shardRoot.resolve("jacoco.exec").toString()));
        assertEquals(shardRoot.resolve("jacoco.xml").toString(), argumentAfter(command, "--xml"));
        assertEquals(shardRoot.resolve("html").toString(), argumentAfter(command, "--html"));
    }

    @Test
    void shardCoverageSanitizesSuiteNameInOutputPaths() throws IOException {
        writeCoverageLockfile();
        List<List<String>> reportCommands = new ArrayList<>();
        CoverageService service = service(
                (projectDirectory, config, cacheRoot, selection, jvmArguments, reportSettings, cliEvents, suiteName, shard) ->
                        new TestRunResult(null, "Tests passed\n"),
                reportCommands);

        CoverageResult result = service.runCoverage(
                projectDir,
                config(),
                projectDir.resolve("cache"),
                TestSelection.empty(),
                CoverageReportSettings.defaults(),
                List.of(),
                "fast suite!",
                new TestShardSpec(2, 4));

        Path shardRoot = projectDir.resolve("target/coverage/shards/fast_suite_/shard-2-of-4").toAbsolutePath().normalize();
        assertEquals(shardRoot.resolve("jacoco.exec"), result.execFile());
        assertEquals(Optional.of(shardRoot.resolve("jacoco.xml")), result.xmlReport());
        assertEquals(Optional.of(shardRoot.resolve("html")), result.htmlDirectory());
        assertEquals(shardRoot.resolve("jacoco.xml").toString(), argumentAfter(reportCommands.getFirst(), "--xml"));
    }

    @Test
    void omittedCoverageSettingsDefaultUnderBuildOutputRoot() throws IOException {
        writeCoverageLockfile();
        List<TestReportSettings> testReportSettings = new ArrayList<>();
        List<List<String>> reportCommands = new ArrayList<>();
        CoverageService service = service(
                (projectDirectory, config, cacheRoot, selection, jvmArguments, reportSettings, cliEvents, suiteName, shard) -> {
                    testReportSettings.add(reportSettings);
                    return new TestRunResult(null, "Tests passed\n");
                },
                reportCommands);

        ProjectConfig config = configWithBuild(new BuildSettings(
                "src/main/java",
                "src/test/java",
                ".zolt/build",
                ".zolt/build/classes",
                ".zolt/build/test-classes"));
        CoverageResult result = service.runCoverage(
                projectDir,
                config,
                projectDir.resolve("cache"),
                TestSelection.empty(),
                null,
                List.of());

        Path execFile = projectDir.resolve(".zolt/build/coverage/jacoco.exec").toAbsolutePath().normalize();
        assertEquals(execFile, result.execFile());
        assertEquals(Optional.of(projectDir.resolve(".zolt/build/coverage/jacoco.xml").toAbsolutePath().normalize()), result.xmlReport());
        assertEquals(Optional.of(projectDir.resolve(".zolt/build/coverage/html").toAbsolutePath().normalize()), result.htmlDirectory());
        assertEquals(Optional.of(Path.of(".zolt/build/coverage/test-reports")), testReportSettings.getFirst().reportsDirectory());
        List<String> command = reportCommands.getFirst();
        assertEquals(projectDir.resolve(".zolt/build/classes").toAbsolutePath().normalize().toString(), argumentAfter(command, "--classfiles"));
        assertEquals(projectDir.resolve(".zolt/build/coverage/jacoco.xml").toAbsolutePath().normalize().toString(), argumentAfter(command, "--xml"));
        assertEquals(projectDir.resolve(".zolt/build/coverage/html").toAbsolutePath().normalize().toString(), argumentAfter(command, "--html"));
    }

    private CoverageService service(
            CoverageTestRunner testRunner,
            List<List<String>> reportCommands) {
        return new CoverageService(
                testRunner,
                new sh.zolt.lockfile.toml.ZoltLockfileReader(),
                requiredVersion -> new JdkStatus(
                        Optional.of(projectDir),
                        Optional.of(Path.of("/java")),
                        Optional.of(Path.of("/javac")),
                        Optional.of(Path.of("/jar")),
                        Optional.of(requiredVersion),
                        requiredVersion),
                new JavaRunner(":", new JavaRunner.ProcessRunner() {
                    @Override
                    public JavaRunner.ProcessResult run(
                            List<String> command,
                            java.util.function.Consumer<String> outputConsumer) {
                        reportCommands.add(command);
                        return new JavaRunner.ProcessResult(0, "Wrote coverage reports\n");
                    }

                    @Override
                    public JavaRunner.ProcessResult run(
                            List<String> command,
                            Map<String, String> environment,
                            java.util.function.Consumer<String> outputConsumer) {
                        reportCommands.add(command);
                        return new JavaRunner.ProcessResult(0, "Wrote coverage reports\n");
                    }
                }),
                (projectDirectory, config, cacheRoot) -> {
                });
    }

    private void writeCoverageLockfile() throws IOException {
        write(projectDir, """
                version = 7

                [[package]]
                id = "org.jacoco:org.jacoco.agent"
                version = "0.8.14"
                source = "maven-central"
                scope = "tool-coverage"
                direct = false
                jar = "org/jacoco/org.jacoco.agent/0.8.14/org.jacoco.agent-0.8.14-runtime.jar"
                dependencies = []

                [[package]]
                id = "org.jacoco:org.jacoco.cli"
                version = "0.8.14"
                source = "maven-central"
                scope = "tool-coverage"
                direct = false
                jar = "org/jacoco/org.jacoco.cli/0.8.14/org.jacoco.cli-0.8.14.jar"
                dependencies = []
                """);
    }

    private static ProjectConfig config() {
        return configWithBuild(BuildSettings.defaults());
    }

    private static ProjectConfig configWithBuild(BuildSettings buildSettings) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main")),
                Map.of("central", "https://repo.maven.apache.org/maven2"),
                Map.of(),
                Map.of("org.junit.jupiter:junit-jupiter", "5.11.4"),
                buildSettings);
    }

    private static String argumentAfter(List<String> command, String argument) {
        int index = command.indexOf(argument);
        if (index < 0 || index + 1 >= command.size()) {
            throw new AssertionError("Missing argument " + argument + " in " + command);
        }
        return command.get(index + 1);
    }

}
