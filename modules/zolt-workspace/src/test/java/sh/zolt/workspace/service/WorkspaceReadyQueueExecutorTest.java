package sh.zolt.workspace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.BuildException;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.cancel.BuildCancellation;
import sh.zolt.classpath.Classpath;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import sh.zolt.workspace.WorkspaceConfig;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceReadyQueueExecutorTest {
    @TempDir
    private Path tempDir;

    @Test
    void startsDependentAsSoonAsItsOwnDependencyCompletes() {
        List<String> members = List.of("modules/slow", "modules/fast", "apps/fast-child");
        Workspace workspace = workspace(
                members,
                List.of(new WorkspaceProjectEdge(
                        "apps/fast-child",
                        "modules/fast",
                        "compile",
                        "com.acme:fast")));
        WorkspaceBuildBatchPlanner.Plan plan =
                new WorkspaceBuildBatchPlanner().plan(workspace, members);
        CountDownLatch childStarted = new CountDownLatch(1);
        AtomicBoolean childWasInvalidated = new AtomicBoolean();

        WorkspaceReadyQueueExecutor.Result<String> result =
                new WorkspaceReadyQueueExecutor().execute(plan, 2, (member, invalidated) -> {
                    if ("modules/slow".equals(member)) {
                        try {
                            assertTrue(
                                    childStarted.await(2, TimeUnit.SECONDS),
                                    "The dependent stayed behind an unrelated slow member.");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(exception);
                        }
                    }
                    if ("apps/fast-child".equals(member)) {
                        childWasInvalidated.set(invalidated);
                        childStarted.countDown();
                    }
                    return new WorkspaceReadyQueueExecutor.TaskResult<>(
                            member,
                            "modules/fast".equals(member));
                });

        assertEquals(3, result.resultsByMember().size());
        assertEquals(2, result.readyQueuePeak());
        assertTrue(result.schedulerIdleNanos() >= 0L);
        assertTrue(childWasInvalidated.get());
    }

    @Test
    void waitsForInterruptedSiblingAfterMemberFailure() throws Exception {
        List<String> members =
                List.of("modules/failing", "modules/sibling");
        WorkspaceBuildBatchPlanner.Plan plan =
                new WorkspaceBuildBatchPlanner().plan(
                        workspace(members, List.of()),
                        members);
        CountDownLatch siblingStarted = new CountDownLatch(1);
        CountDownLatch siblingInterrupted = new CountDownLatch(1);
        CountDownLatch allowSiblingFinish = new CountDownLatch(1);
        CountDownLatch executeFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                new WorkspaceReadyQueueExecutor().execute(
                        plan,
                        2,
                        (member, invalidated) -> {
                            if ("modules/failing".equals(member)) {
                                await(siblingStarted);
                                throw new BuildException("boom");
                            }
                            siblingStarted.countDown();
                            try {
                                new CountDownLatch(1).await();
                                throw new AssertionError(
                                        "Sibling build should be interrupted.");
                            } catch (InterruptedException exception) {
                                siblingInterrupted.countDown();
                                await(allowSiblingFinish);
                                return new WorkspaceReadyQueueExecutor.TaskResult<>(
                                        member,
                                        false);
                            }
                        });
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                executeFinished.countDown();
            }
        });

        try {
            assertTrue(
                    siblingInterrupted.await(2, TimeUnit.SECONDS),
                    "Sibling build was not interrupted.");
            assertFalse(
                    executeFinished.await(100, TimeUnit.MILLISECONDS),
                    "Ready-queue execution returned before its sibling stopped.");
        } finally {
            allowSiblingFinish.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertFalse(caller.isAlive(), "Ready-queue caller did not stop.");
        assertTrue(failure.get() instanceof BuildException);
        assertEquals("boom", failure.get().getMessage());
    }

    @Test
    void cancelsRegisteredSiblingResourceBeforeWaitingForShutdown() throws Exception {
        List<String> members =
                List.of("modules/failing", "modules/sibling");
        WorkspaceBuildBatchPlanner.Plan plan =
                new WorkspaceBuildBatchPlanner().plan(
                        workspace(members, List.of()),
                        members);
        CountDownLatch siblingRegistered = new CountDownLatch(1);
        CountDownLatch siblingReleased = new CountDownLatch(1);

        BuildException failure = org.junit.jupiter.api.Assertions.assertThrows(
                BuildException.class,
                () -> new WorkspaceReadyQueueExecutor().execute(
                        plan,
                        2,
                        (member, invalidated) -> {
                            if ("modules/failing".equals(member)) {
                                await(siblingRegistered);
                                throw new BuildException("boom");
                            }
                            try (BuildCancellation.Registration ignored =
                                    BuildCancellation.onCancel(siblingReleased::countDown)) {
                                siblingRegistered.countDown();
                                await(siblingReleased);
                                return new WorkspaceReadyQueueExecutor.TaskResult<>(
                                        member,
                                        false);
                            }
                        }));

        assertEquals("boom", failure.getMessage());
        assertEquals(0L, siblingReleased.getCount());
    }

    @Test
    void failedSiblingTerminatesJavaProcessTreeAndReleasesWorkspaceLock()
            throws Exception {
        List<String> members =
                List.of("modules/failing", "modules/java");
        WorkspaceBuildBatchPlanner.Plan plan =
                new WorkspaceBuildBatchPlanner().plan(
                        workspace(members, List.of()),
                        members);
        String testClasspath =
                classLocation(WorkspaceReadyQueueExecutorTest.class);
        Path readyFile = tempDir.resolve("java-ready.txt");
        Path cancellationFile = tempDir.resolve("java-cancelled.txt");

        BuildException failure;
        try (WatchService readyEvents = FileSystems.getDefault().newWatchService();
                WorkspaceMutationLock ignored =
                        WorkspaceMutationLock.acquire(tempDir)) {
            tempDir.register(readyEvents, StandardWatchEventKinds.ENTRY_CREATE);
            failure = assertThrows(
                    BuildException.class,
                    () -> new WorkspaceReadyQueueExecutor().execute(
                            plan,
                            2,
                            (member, invalidated) -> {
                                if ("modules/failing".equals(member)) {
                                    awaitProcessIds(readyEvents, readyFile);
                                    throw new BuildException("boom");
                                }
                                try (BuildCancellation.Registration cancellationObserved =
                                        BuildCancellation.onCancel(
                                                () -> writeMarker(cancellationFile))) {
                                    new JavaRunner().run(
                                            javaExecutable(),
                                            new Classpath(List.of(Path.of(testClasspath))),
                                            BlockingJavaProcess.class.getName(),
                                            List.of(
                                                    javaExecutable().toString(),
                                                    testClasspath,
                                                    readyFile.toString()));
                                }
                                return new WorkspaceReadyQueueExecutor.TaskResult<>(
                                        member,
                                        false);
                            }));
        }

        assertEquals("boom", failure.getMessage());
        assertTrue(Files.isRegularFile(cancellationFile));
        for (long processId : processIds(readyFile)) {
            awaitStopped(processId);
        }
        Process lockProbe = startLockProbe();
        try {
            assertTrue(
                    lockProbe.waitFor(5, TimeUnit.SECONDS),
                    "A second process could not reacquire the workspace lock.");
            assertEquals(0, lockProbe.exitValue());
        } finally {
            if (lockProbe.isAlive()) {
                lockProbe.destroyForcibly();
            }
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    latch.await();
                    return;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitProcessIds(
            WatchService events,
            Path readyFile) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (hasCompleteProcessIds(readyFile)) {
                return;
            }
            try {
                WatchKey key = events.poll(50, TimeUnit.MILLISECONDS);
                if (key != null) {
                    key.reset();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
        }
        throw new AssertionError("Java child did not publish both process IDs.");
    }

    private static boolean hasCompleteProcessIds(Path readyFile) {
        if (!Files.isRegularFile(readyFile)) {
            return false;
        }
        try {
            String[] values = Files.readString(readyFile).strip().split(",", -1);
            if (values.length != 2) {
                return false;
            }
            Long.parseLong(values[0]);
            Long.parseLong(values[1]);
            return true;
        } catch (IOException | NumberFormatException ignored) {
            return false;
        }
    }

    private static Workspace workspace(List<String> members, List<WorkspaceProjectEdge> edges) {
        List<WorkspaceMember> workspaceMembers = members.stream()
                .map(member -> new WorkspaceMember(
                        member,
                        Path.of(member),
                        config(projectName(member))))
                .toList();
        return new Workspace(
                Path.of("."),
                Path.of("zolt.toml"),
                new WorkspaceConfig("workspace", members, List.of(), Map.of(), Map.of()),
                workspaceMembers,
                edges,
                members);
    }

    private static String projectName(String member) {
        return member.substring(member.lastIndexOf('/') + 1);
    }

    private static ProjectConfig config(String name) {
        return ProjectConfigs.withDirectDependencies(
                new ProjectMetadata(name, "0.1.0", "com.acme", "21", Optional.empty()),
                Map.of(),
                Map.of(),
                Map.of(),
                BuildSettings.defaults());
    }

    private Process startLockProbe() throws IOException, URISyntaxException {
        return new ProcessBuilder(
                        javaExecutable().toString(),
                        "-cp",
                        classLocation(WorkspaceReadyQueueExecutorTest.class),
                        LockProbe.class.getName(),
                        tempDir.toString())
                .redirectErrorStream(true)
                .start();
    }

    private static long[] processIds(Path readyFile) throws IOException {
        String[] values = Files.readString(readyFile).strip().split(",", -1);
        assertEquals(2, values.length);
        return new long[] {
                Long.parseLong(values[0]),
                Long.parseLong(values[1])
        };
    }

    private static void awaitStopped(long processId) throws InterruptedException {
        Optional<ProcessHandle> process = ProcessHandle.of(processId);
        if (process.isPresent() && process.orElseThrow().isAlive()) {
            try {
                process.orElseThrow()
                        .onExit()
                        .get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException
                    | java.util.concurrent.TimeoutException exception) {
                throw new AssertionError(
                        "Cancelled Java process is still alive: " + processId,
                        exception);
            }
        }
    }

    private static void writeMarker(Path path) {
        try {
            Files.writeString(path, "cancelled");
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String classLocation(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
    }

    private static Path javaExecutable() {
        return Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase()
                .contains("win");
    }

    public static final class BlockingJavaProcess {
        private BlockingJavaProcess() {
        }

        public static void main(String[] arguments) throws Exception {
            Process descendant = new ProcessBuilder(
                            arguments[0],
                            "-classpath",
                            arguments[1],
                            BlockingDescendant.class.getName())
                    .start();
            Files.writeString(
                    Path.of(arguments[2]),
                    ProcessHandle.current().pid() + "," + descendant.pid());
            new CountDownLatch(1).await();
        }
    }

    public static final class BlockingDescendant {
        private BlockingDescendant() {
        }

        public static void main(String[] arguments) throws InterruptedException {
            new CountDownLatch(1).await();
        }
    }

    public static final class LockProbe {
        private LockProbe() {
        }

        public static void main(String[] arguments) throws IOException {
            Path lockPath = Path.of(arguments[0])
                    .resolve(".zolt")
                    .resolve("workspace-mutation.lock");
            try (FileChannel channel = FileChannel.open(
                            lockPath,
                            StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                // Acquiring the lock is the probe.
            }
        }
    }
}
