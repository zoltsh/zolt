package sh.zolt.cli.task;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.cli.CliTestSupport;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TaskCommandTest {
    @TempDir
    private Path tempDir;

    @Test
    void listsConfiguredTasks() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("task-list") + """

                [tasks.fmt]
                description = "Format Java sources"
                run = ["scripts/format"]

                [tasks.docs]
                run = ["python3", "-m", "http.server"]
                """);

        CommandResult result = execute("tasks", "--cwd", tempDir.toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("Tasks:"));
        assertTrue(result.stdout().contains("fmt"));
        assertTrue(result.stdout().contains("Format Java sources"));
        assertTrue(result.stdout().contains("docs"));
        assertEquals("", result.stderr());
    }

    @Test
    void runsTaskWithConfiguredEnvironmentCwdAndPassthroughArguments() throws IOException {
        Path script = writeScript("scripts/echo-task.sh", """
                printf 'cwd=%s\\n' "$PWD"
                printf 'env=%s\\n' "$APP_ENV"
                printf 'args=%s\\n' "$*"
                """);
        Files.createDirectories(tempDir.resolve("work"));
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("task-run") + """

                [tasks.echo]
                run = ["sh", "../scripts/echo-task.sh"]
                cwd = "work"
                env = { APP_ENV = "local" }
                """);

        CommandResult result = execute(
                "task",
                "--cwd", tempDir.toString(),
                "echo",
                "--",
                "--flag",
                "value");

        assertTrue(Files.exists(script));
        assertEquals(0, result.exitCode());
        assertTrue(result.stdout().contains("cwd=" + tempDir.resolve("work").toRealPath()));
        assertTrue(result.stdout().contains("env=local"));
        assertTrue(result.stdout().contains("args=--flag value"));
        assertEquals("", result.stderr());
    }

    @Test
    void propagatesStartedTaskExitCodeAndStreamsChildOutput() throws IOException {
        writeScript("fail.sh", """
                printf 'child stdout\\n'
                printf 'child stderr\\n' >&2
                exit 7
                """);
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("task-fail") + """

                [tasks.fail]
                run = ["sh", "fail.sh"]
                """);

        CommandResult result = execute("task", "--cwd", tempDir.toString(), "fail");

        assertEquals(7, result.exitCode());
        assertTrue(result.stdout().contains("child stdout"));
        assertTrue(result.stderr().contains("child stderr"));
        assertTrue(result.stderr().contains("error: Task `fail` exited with code 7."));
    }

    @Test
    void interruptionTerminatesTaskAndItsDescendants() throws Exception {
        assumeFalse(isWindows(), "POSIX signal fixtures require /bin/sh");
        Path parentPid = tempDir.resolve("parent.pid");
        Path childPid = tempDir.resolve("child.pid");
        Path grandchildPid = tempDir.resolve("grandchild.pid");
        Path ready = tempDir.resolve("ready");
        writeScript("tree.sh", """
                echo $$ > parent.pid
                (
                  trap '' TERM
                  sleep 30 &
                  echo $! > grandchild.pid
                  wait
                ) &
                echo $! > child.pid
                while [ ! -s grandchild.pid ]; do :; done
                : > ready
                wait
                """);
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("task-interrupt") + """

                [tasks.forest]
                run = ["sh", "tree.sh"]
                """);
        AtomicReference<CommandResult> result = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() ->
                result.set(execute("task", "--cwd", tempDir.toString(), "forest")));

        awaitFile(ready);
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(caller.isAlive(), "Task command remained blocked after interruption.");
        assertNotNull(result.get());
        assertEquals(1, result.get().exitCode());
        assertTrue(result.get().stderr().contains("Task `forest` was interrupted"), result.get().stderr());
        assertStopped(List.of(parentPid, childPid, grandchildPid));
    }

    @Test
    void reportsUnknownTasksWithAvailableNames() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("task-missing") + """

                [tasks.fmt]
                run = ["scripts/format"]
                """);

        CommandResult result = execute("task", "--cwd", tempDir.toString(), "docs");

        assertEquals(1, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("error: Unknown task `docs`"));
        assertTrue(result.stderr().contains("Available tasks: fmt."));
        assertTrue(result.stderr().contains("Run `zolt tasks`"));
    }

    @Test
    void rejectsTaskCwdThatEscapesThroughSymlink() throws IOException {
        Path outside = Files.createDirectories(tempDir.resolveSibling(tempDir.getFileName() + "-outside"));
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are not available");
        }
        Files.writeString(tempDir.resolve("zolt.toml"), CliTestSupport.memberConfig("task-cwd") + """

                [tasks.escape]
                run = ["sh", "-c", "printf should-not-run"]
                cwd = "link"
                """);

        CommandResult result = execute("task", "--cwd", tempDir.toString(), "escape");

        assertEquals(1, result.exitCode());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().contains("error: Invalid cwd for task `escape`"));
        assertTrue(result.stderr().contains("Task working directories must stay under"));
    }

    @Test
    void listsWorkspaceRootAndMemberTasksFromMemberDirectory() throws IOException {
        Path member = writeWorkspace("""
                [tasks.root-task]
                description = "Root task"
                run = ["scripts/root-task"]
                """, """
                [tasks.member-task]
                description = "Member task"
                run = ["scripts/member-task"]
                """);

        CommandResult result = execute("tasks", "--cwd", member.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("root-task"), result.stdout());
        assertTrue(result.stdout().contains("Root task"));
        assertTrue(result.stdout().contains("member-task"), result.stdout());
        assertTrue(result.stdout().contains("Member task"));
    }

    @Test
    void memberTaskResolvesCwdAgainstMemberDirectory() throws IOException {
        Path member = writeWorkspace("", """
                [tasks.where]
                run = ["sh", "-c", "printf 'cwd=%s\\\\n' \\"$PWD\\"; printf 'root=%s\\\\n' \\"$ZOLT_PROJECT_ROOT\\""]
                cwd = "work"
                """);
        Files.createDirectories(member.resolve("work"));

        CommandResult result = execute("task", "--cwd", member.toString(), "where");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(
                result.stdout().contains("cwd=" + member.resolve("work").toRealPath()),
                result.stdout());
        assertTrue(result.stdout().contains("root=" + member), result.stdout());
    }

    @Test
    void rootTaskResolvesCwdAgainstWorkspaceRootFromMemberDirectory() throws IOException {
        Path member = writeWorkspace("""
                [tasks.where]
                run = ["sh", "-c", "printf 'cwd=%s\\\\n' \\"$PWD\\""]
                cwd = "tools"
                """, "");
        Files.createDirectories(tempDir.resolve("tools"));

        CommandResult result = execute("task", "--cwd", member.toString(), "where");

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(
                result.stdout().contains("cwd=" + tempDir.resolve("tools").toRealPath()),
                result.stdout());
    }

    @Test
    void rejectsRootAndMemberTaskIdCollision() throws IOException {
        Path member = writeWorkspace("""
                [tasks.build-docs]
                run = ["scripts/root-docs"]
                """, """
                [tasks.build-docs]
                run = ["scripts/member-docs"]
                """);

        CommandResult result = execute("tasks", "--cwd", member.toString());

        assertEquals(1, result.exitCode());
        assertTrue(result.stderr().contains("build-docs"), result.stderr());
        assertTrue(result.stderr().contains("cannot"), result.stderr());
    }

    /** A two-file workspace: a virtual root plus one member at {@code apps/api}. */
    private Path writeWorkspace(String rootCommands, String memberCommands) throws IOException {
        Path member = tempDir.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [workspace]
                name = "tasks-workspace"

                [workspace.members]
                include = ["apps/api"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = %s

                """.formatted(Runtime.version().feature()) + rootCommands);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"

                """ + memberCommands);
        return member;
    }

    private Path writeScript(String relativePath, String body) throws IOException {
        Path script = tempDir.resolve(relativePath);
        Files.createDirectories(script.getParent());
        Files.writeString(script, "#!/usr/bin/env sh\n" + body);
        script.toFile().setExecutable(true);
        return script;
    }

    private static void awaitFile(Path path) throws Exception {
        try (WatchService watcher = path.getFileSystem().newWatchService()) {
            path.getParent().register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            if (Files.exists(path)) {
                return;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(path)) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0L, "Task process tree did not become ready.");
                WatchKey key = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                assertNotNull(key, "Task process tree did not become ready.");
                key.pollEvents();
                key.reset();
            }
        }
    }

    private static void assertStopped(List<Path> pidFiles) throws Exception {
        for (Path pidFile : pidFiles) {
            long pid = Long.parseLong(Files.readString(pidFile).strip());
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle != null && handle.isAlive()) {
                handle.onExit().get(5, TimeUnit.SECONDS);
            }
            assertTrue(
                    ProcessHandle.of(pid).map(process -> !process.isAlive()).orElse(true),
                    "Interrupted task descendant is still alive: " + pid);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
