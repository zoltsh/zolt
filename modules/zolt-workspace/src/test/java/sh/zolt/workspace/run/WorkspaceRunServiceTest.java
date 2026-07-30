package sh.zolt.workspace.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.RunException;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.resolve.ResolveService;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceRunServiceTest {
    private final WorkspaceRunService service = new WorkspaceRunService();

    @TempDir
    private Path tempDir;

    @Test
    void runsSelectedWorkspaceApplicationWithWorkspaceRuntimeClasspath() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);
        member("apps/api", "api", """
                main = "com.acme.api.Api"

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class Api {
                    private Api() {
                    }

                    public static void main(String[] args) {
                        System.out.println(Core.message() + ":" + args[0]);
                    }
                }
                """);
        List<String> streamed = new ArrayList<>();

        WorkspaceRunResult result = service.run(
                tempDir.resolve("apps/api"),
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(false, List.of("apps/api")),
                List.of("hello"),
                streamed::add);

        assertTrue(result.resolvedLockfile());
        assertEquals(List.of("modules/core", "apps/api"), result.builtMembers().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());
        assertTrue(result.builtMembers().get(1).classpaths().runtime().entries()
                .contains(tempDir.resolve("modules/core/target/classes")));
        assertEquals(List.of("apps/api"), result.members().stream()
                .map(WorkspaceRunResult.MemberRunResult::member)
                .toList());
        assertEquals("core:hello\n", result.members().getFirst().result().javaRunResult().output());
        assertEquals(List.of("core:hello\n"), streamed);
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class")));
        assertTrue(Files.exists(tempDir.resolve("modules/core/target/classes/com/acme/core/Core.class")));
    }

    @Test
    void selectedMemberWithoutMainClassProducesActionableError() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["modules/core"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                }
                """);

        RunException exception = assertThrows(
                RunException.class,
                () -> service.run(
                        tempDir,
                        tempDir.resolve("cache"),
                        new WorkspaceSelectionRequest(false, List.of("modules/core")),
                        List.of(),
                        ignored -> {
                        }));

        assertEquals(
                "Workspace member `modules/core` has no main class configured. Add [project].main to its zolt.toml or choose an application member.",
                exception.getMessage());
    }

    @Test
    void sharesCachedJdkDetectionAcrossWorkspaceBuildAndLaunch() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                    public static String message() {
                        return "core";
                    }
                }
                """);
        member("apps/api", "api", """
                main = "com.acme.api.Api"

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class Api {
                    public static void main(String[] args) {
                        System.out.println(Core.message());
                    }
                }
                """);
        CachingJdkChecker jdkChecker = new CachingJdkChecker();
        WorkspaceRunService service = new WorkspaceRunService(jdkChecker);

        service.run(
                tempDir,
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(false, List.of("apps/api")),
                List.of(),
                ignored -> {
                });

        assertEquals(2, jdkChecker.detectCalls());
        assertEquals(1, jdkChecker.toolchainReads());
    }

    @Test
    void stagedSnapshotWaitsForWorkspaceLease() throws Exception {
        workspace("""
                [workspace]
                name = "staged-run"
                members = ["apps/api"]
                """);
        member("apps/api", "api", """
                main = "com.acme.api.Api"
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                public final class Api {
                    public static void main(String[] args) {
                    }
                }
                """);
        WorkspaceBuildPlan plan = service.planRun(
                tempDir,
                tempDir.resolve("cache"),
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult buildResult =
                service.buildRunInputs(plan, tempDir.resolve("cache"));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<WorkspaceRunSnapshot> snapshot =
                new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker;

        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(tempDir)) {
            worker = Thread.ofPlatform().start(() -> {
                started.countDown();
                try {
                    snapshot.set(service.snapshotRun(plan, buildResult));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    completed.countDown();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(
                    completed.await(200, TimeUnit.MILLISECONDS),
                    "public staged snapshot did not wait for the workspace lease");
        }

        assertTrue(completed.await(10, TimeUnit.SECONDS));
        worker.join();
        assertEquals(null, failure.get());
        try (WorkspaceRunSnapshot ignored = snapshot.get()) {
            assertTrue(ignored.members().size() == 1);
        }
    }

    @Test
    void releasesWorkspaceLeaseBeforeLaunchAndRunsFromStableClassSnapshot() throws Exception {
        workspace("""
                [workspace]
                name = "lease-free-run"
                members = ["apps/api"]
                """);
        member("apps/api", "api", """
                main = "com.acme.api.Api"
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                public final class Api {
                    public static void main(String[] args) {
                        System.out.println("snapshot");
                    }
                }
                """);
        CountDownLatch launched = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<List<String>> command = new AtomicReference<>();
        JavaRunner javaRunner = new JavaRunner(
                File.pathSeparator,
                (arguments, output) -> {
                    command.set(arguments);
                    launched.countDown();
                    await(release);
                    return new JavaRunner.ProcessResult(0, "snapshot\n");
                });
        CachingJdkChecker jdkChecker = new CachingJdkChecker();
        WorkspaceRunService service = new WorkspaceRunService(
                new WorkspaceBuildService(jdkChecker, new ResolveService()),
                jdkChecker,
                javaRunner);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread application = Thread.ofPlatform().start(() -> {
            try {
                service.run(
                        tempDir,
                        tempDir.resolve("cache"),
                        new WorkspaceSelectionRequest(false, List.of("apps/api")),
                        List.of(),
                        ignored -> {
                        });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        try {
            assertTrue(launched.await(10, TimeUnit.SECONDS));
            CountDownLatch reacquired = new CountDownLatch(1);
            Thread mutator = Thread.ofPlatform().start(() -> {
                try (WorkspaceMutationLock ignored =
                        WorkspaceMutationLock.acquire(tempDir)) {
                    reacquired.countDown();
                }
            });
            assertTrue(
                    reacquired.await(2, TimeUnit.SECONDS),
                    "workspace lease remained held while the application was running");
            mutator.join();

            Path snapshotClasses = classpathEntries(command.get()).stream()
                    .filter(path -> path.toString().contains(
                            ".zolt" + File.separator + "run"))
                    .findFirst()
                    .orElseThrow();
            Path snapshotClass = snapshotClasses.resolve("com/acme/api/Api.class");
            byte[] stableClass = Files.readAllBytes(snapshotClass);
            Files.write(
                    tempDir.resolve("apps/api/target/classes/com/acme/api/Api.class"),
                    new byte[] {0});
            assertTrue(stableClass.length > 1);
            assertTrue(java.util.Arrays.equals(
                    stableClass,
                    Files.readAllBytes(snapshotClass)));
        } finally {
            release.countDown();
            application.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertTrue(!application.isAlive());
        assertEquals(null, failure.get());
    }

    private static List<Path> classpathEntries(List<String> command) {
        int index = command.indexOf("-classpath");
        return List.of(command.get(index + 1).split(
                        java.util.regex.Pattern.quote(File.pathSeparator)))
                .stream()
                .map(Path::of)
                .toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), content);
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "%s"
                %s""".formatted(name, currentJavaMajorVersion(), extraToml));
    }

    private void source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }

    private static final class CachingJdkChecker implements JdkChecker {
        private int detectCalls;
        private int toolchainReads;
        private JdkStatus status;

        @Override
        public JdkStatus detect(String requiredVersion) {
            detectCalls++;
            if (status == null) {
                toolchainReads++;
                Path javaHome = Path.of(System.getProperty("java.home"));
                status = new JdkStatus(
                        Optional.of(javaHome),
                        Optional.of(javaHome.resolve("bin").resolve(executable("java"))),
                        Optional.of(javaHome.resolve("bin").resolve(executable("javac"))),
                        Optional.of(javaHome.resolve("bin").resolve(executable("jar"))),
                        Optional.of(requiredVersion),
                        requiredVersion);
            }
            return status;
        }

        int detectCalls() {
            return detectCalls;
        }

        int toolchainReads() {
            return toolchainReads;
        }
    }
}
