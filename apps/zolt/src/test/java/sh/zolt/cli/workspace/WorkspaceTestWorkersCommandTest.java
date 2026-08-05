package sh.zolt.cli.workspace;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers {@code --test-workers}: validation, deterministic output above the old cap, and metrics. */
final class WorkspaceTestWorkersCommandTest {
    private static final int MEMBERS = 8;

    @TempDir
    private Path tempDir;

    @Test
    void rejectsAWorkerCountBelowOne() throws IOException {
        Workspace workspace = workspace("reject-zero", MEMBERS, -1);

        CommandResult result = workspace.run("--test-workers", "0");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Invalid --test-workers `0`."), result.stderr());
        assertTrue(result.stderr().contains("Use a positive integer."), result.stderr());
    }

    @Test
    void rejectsAWorkerCountThatIsNotANumber() throws IOException {
        Workspace workspace = workspace("reject-text", MEMBERS, -1);

        CommandResult result = workspace.run("--test-workers", "lots");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Invalid --test-workers `lots`."), result.stderr());
        assertTrue(result.stderr().contains("Use a positive integer."), result.stderr());
    }

    @Test
    void rejectsAWorkerCountAboveTheCeiling() throws IOException {
        Workspace workspace = workspace("reject-huge", MEMBERS, -1);

        CommandResult result = workspace.run("--test-workers", "65");

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("Invalid --test-workers `65`."), result.stderr());
        assertTrue(result.stderr().contains("Use a value between 1 and 64."), result.stderr());
    }

    @Test
    void concurrencyAboveTheOldCapKeepsOutputAndSummaryIdenticalToSerial() throws IOException {
        Workspace serial = workspace("serial", MEMBERS, -1);
        Workspace parallel = workspace("parallel", MEMBERS, -1);

        CommandResult serialResult = serial.run("--test-workers", "1");
        CommandResult parallelResult = parallel.run("--test-workers", "8");

        assertEquals(0, serialResult.exitCode());
        assertEquals(0, parallelResult.exitCode());
        assertEquals(
                serial.memberLines(serialResult),
                parallel.memberLines(parallelResult),
                "member output must not reorder when the pool widens");
        assertTrue(
                parallelResult.stdout().contains("Tests passed for " + MEMBERS + " workspace members"),
                parallelResult.stdout());
    }

    @Test
    void failingMemberAtHighConcurrencyStillFailsAndNamesTheMember() throws IOException {
        Workspace workspace = workspace("failing", MEMBERS, 5);

        CommandResult result = workspace.run("--test-workers", "8");

        assertNotEquals(0, result.exitCode());
        assertTrue(
                result.stderr().contains("modules/member5"),
                result.stderr());
    }

    @Test
    void reportsPoolWidthAndWorkerTimingsInTimings() throws IOException {
        Workspace workspace = workspace("metrics", MEMBERS, -1);

        CommandResult result = workspace.run("--test-workers", "6");

        assertEquals(0, result.exitCode());
        String runPhase = result.stderr().lines()
                .filter(line -> line.contains("\"phase\":\"run workspace test members\""))
                .findFirst()
                .orElseThrow();
        assertTrue(runPhase.contains("\"testWorkerConcurrency\":\"6\""), runPhase);
        assertTrue(runPhase.contains("\"testWorkerStartupNanos\""), runPhase);
        assertTrue(runPhase.contains("\"testWorkerRequestNanos\""), runPhase);
        assertTrue(runPhase.contains("\"testWorkerQueueNanos\""), runPhase);
        assertTrue(runPhase.contains("\"testWorkerStarts\""), runPhase);
    }

    @Test
    void adaptiveDefaultWidensBeyondTheOldCapOfFour() throws IOException {
        Workspace workspace = workspace("adaptive", MEMBERS, -1);

        CommandResult result = workspace.run();

        assertEquals(0, result.exitCode());
        String runPhase = result.stderr().lines()
                .filter(line -> line.contains("\"phase\":\"run workspace test members\""))
                .findFirst()
                .orElseThrow();
        int concurrency = concurrency(runPhase);
        assertTrue(
                concurrency > 4 || concurrency == MEMBERS,
                "expected the adaptive default to exceed the old cap, got " + concurrency);
        assertTrue(concurrency <= MEMBERS, "never more workers than members, got " + concurrency);
    }

    private static int concurrency(String runPhase) {
        String key = "\"testWorkerConcurrency\":\"";
        int start = runPhase.indexOf(key) + key.length();
        return Integer.parseInt(runPhase.substring(start, runPhase.indexOf('"', start)));
    }

    private Workspace workspace(String name, int members, int failingMember) throws IOException {
        Path workspaceDir = tempDir.resolve(name);
        Path cacheRoot = tempDir.resolve(name + "-cache");
        writeFakeConsoleJar(cacheRoot.resolve(
                "org/junit/platform/junit-platform-console-standalone/1.11.4/"
                        + "junit-platform-console-standalone-1.11.4.jar"));
        List<String> memberPaths = new ArrayList<>();
        for (int index = 0; index < members; index++) {
            memberPaths.add("modules/member" + index);
        }
        Files.createDirectories(workspaceDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"
                members = ["%s"]
                """.formatted(String.join("\", \"", memberPaths)));
        for (int index = 0; index < members; index++) {
            writeMember(workspaceDir, index, index == failingMember);
        }
        WorkspaceTestCommandTestSupport.writeWorkspaceTestLockfile(
                workspaceDir,
                memberPaths.toArray(String[]::new));
        return new Workspace(workspaceDir, cacheRoot, memberPaths);
    }

    private static void writeMember(Path workspaceDir, int index, boolean failing) throws IOException {
        String name = "member" + index;
        Path memberDir = workspaceDir.resolve("modules/" + name);
        Files.createDirectories(memberDir);
        Files.writeString(memberDir.resolve("zolt.toml"), memberConfig(name) + testToolchain());
        Path source = memberDir.resolve("src/main/java/com/example/" + name + "/Value.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example.%s;

                public final class Value {
                    private Value() {
                    }

                    public static String message() {
                        return "%s";
                    }
                }
                """.formatted(name, name));
        Path test = memberDir.resolve("src/test/java/com/example/" + name + "/ValueTest.java");
        Files.createDirectories(test.getParent());
        // A broken test source fails inside the member task, which is the pool-parallel region.
        Files.writeString(test, failing
                ? """
                        package com.example.%s;

                        public final class ValueTest {
                            public String message() {
                                return Value.missingMethod()
                            }
                        }
                        """.formatted(name)
                : """
                        package com.example.%s;

                        public final class ValueTest {
                            public String message() {
                                return Value.message();
                            }
                        }
                        """.formatted(name));
    }

    private static String testToolchain() {
        return """

                [toolchain.java]
                version = "%d"
                features = []
                policy = "prefer-managed"

                [toolchain.java.test]
                version = "%d"
                """.formatted(
                        Runtime.version().feature(),
                        Runtime.version().feature());
    }

    private record Workspace(Path directory, Path cacheRoot, List<String> memberPaths) {
        CommandResult run(String... extra) {
            List<String> arguments = new ArrayList<>(List.of(
                    "test",
                    "--workspace",
                    "--all",
                    "--timings",
                    "--timings-format", "json",
                    "--cwd", directory.toString(),
                    "--cache-root", cacheRoot.toString()));
            arguments.addAll(List.of(extra));
            return execute(arguments.toArray(String[]::new));
        }

        /** The per-member success lines, in the order the command printed them. */
        List<String> memberLines(CommandResult result) {
            return result.stdout().lines()
                    .filter(line -> line.contains("Tests passed in "))
                    .map(String::strip)
                    .toList();
        }
    }
}
