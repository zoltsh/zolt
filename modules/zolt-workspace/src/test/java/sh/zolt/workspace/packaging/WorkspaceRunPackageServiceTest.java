package sh.zolt.workspace.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.RunPackageException;
import sh.zolt.build.run.JavaRunner;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.project.PackageMode;
import sh.zolt.workspace.service.WorkspacePlanTarget;

final class WorkspaceRunPackageServiceTest {
    private final WorkspaceRunPackageService service = new WorkspaceRunPackageService();

    @TempDir
    private Path tempDir;

    @Test
    void runsSelectedPackagedWorkspaceApplicationWithWorkspaceRuntimeClasspath() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api", "modules/core"]
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
                "com.acme:core" = { workspace = true }
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

        WorkspaceRunPackageResult result = service.runPackages(
                tempDir.resolve("apps/api"),
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(false, List.of("apps/api")),
                List.of("hello"));

        assertTrue(result.resolvedLockfile());
        assertEquals(List.of("modules/core", "apps/api"), result.builtMembers().stream()
                .map(WorkspaceBuildResult.MemberBuildResult::member)
                .toList());
        assertTrue(result.builtMembers().get(1).classpaths().runtime().entries()
                .contains(tempDir.resolve("modules/core/target/classes")));
        assertEquals(List.of("apps/api"), result.members().stream()
                .map(WorkspaceRunPackageResult.MemberRunPackageResult::member)
                .toList());
        assertEquals("core:hello\n", result.members().getFirst().result().javaRunResult().output());
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/api-0.1.0.jar")));
    }

    @Test
    void selectedMemberWithoutMainClassProducesActionableError() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["modules/core"]
                """);
        member("modules/core", "core", "");
        source("modules/core/src/main/java/com/acme/core/Core.java", """
                package com.acme.core;

                public final class Core {
                }
                """);

        RunPackageException exception = assertThrows(
                RunPackageException.class,
                () -> service.runPackages(
                        tempDir,
                        tempDir.resolve("cache"),
                        new WorkspaceSelectionRequest(false, List.of("modules/core")),
                        List.of()));

        assertEquals(
                "Workspace member `modules/core` has no main class configured. Add [project].main to its zolt.toml or choose an application member.",
                exception.getMessage());
    }

    @Test
    void sharesCachedJdkDetectionAcrossWorkspacePackageAndLaunch() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"

                [workspace.members]
                include = ["apps/api", "modules/core"]
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
                "com.acme:core" = { workspace = true }
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
        WorkspaceRunPackageService service = new WorkspaceRunPackageService(jdkChecker);

        service.runPackages(
                tempDir,
                tempDir.resolve("cache"),
                new WorkspaceSelectionRequest(false, List.of("apps/api")),
                List.of());

        assertEquals(2, jdkChecker.detectCalls());
        assertEquals(1, jdkChecker.toolchainReads());
    }

    @Test
    void uberWorkspaceLaunchRunsFromTheArchiveAfterOriginalDependencyOutputIsRemoved()
            throws IOException {
        workspace("""
                [workspace]
                name = "uber-self-contained"

                [workspace.members]
                include = ["apps/api", "modules/core"]
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
                "com.acme:core" = { workspace = true }
                """);
        source("apps/api/src/main/java/com/acme/api/Api.java", """
                package com.acme.api;

                import com.acme.core.Core;

                public final class Api {
                    public static void main(String[] args) {
                        System.out.println(Core.message() + ":" + args[0]);
                    }
                }
                """);
        Path cache = tempDir.resolve("uber-self-contained-cache");
        WorkspaceBuildPlan plan = service.planRunPackages(
                WorkspacePlanTarget.at(tempDir),
                cache,
                new WorkspaceSelectionRequest(
                        false,
                        List.of("apps/api")));
        WorkspaceBuildResult build =
                service.buildRunPackageInputs(plan, cache);
        WorkspacePackageResult packaged =
                service.packageRunPackageInputs(
                        plan,
                        build,
                        cache,
                        Optional.of(PackageMode.UBER));
        deleteTree(tempDir.resolve(
                "modules/core/target/classes"));

        WorkspaceRunPackageResult result =
                service.runPackagedMembers(
                        plan,
                        packaged,
                        List.of("uber"));

        assertEquals(
                "core:uber\n",
                result.members()
                        .getFirst()
                        .result()
                        .javaRunResult()
                        .output());
        assertTrue(Files.notExists(tempDir.resolve(
                "modules/core/target/classes")));
    }

    @Test
    void releasesWorkspaceLeaseBeforePackagedLaunchAndRunsFromStableJarSnapshot()
            throws Exception {
        workspace("""
                [workspace]
                name = "lease-free-package"

                [workspace.members]
                include = ["apps/api"]
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
        WorkspaceRunPackageService service = new WorkspaceRunPackageService(
                new WorkspacePackageService(jdkChecker),
                jdkChecker,
                javaRunner);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread application = Thread.ofPlatform().start(() -> {
            try {
                service.runPackages(
                        tempDir,
                        tempDir.resolve("cache"),
                        new WorkspaceSelectionRequest(false, List.of("apps/api")),
                        List.of());
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
                    "workspace lease remained held while the packaged application was running");
            mutator.join();

            int classpath = command.get().indexOf("-classpath");
            Path snapshotJar = Path.of(command.get()
                            .get(classpath + 1)
                            .split(java.util.regex.Pattern.quote(File.pathSeparator))[0]);
            assertTrue(snapshotJar.toString().contains(
                    ".zolt" + File.separator + "run"));
            byte[] stableJar = Files.readAllBytes(snapshotJar);
            Files.write(
                    tempDir.resolve("apps/api/target/api-0.1.0.jar"),
                    new byte[] {0});
            assertTrue(stableJar.length > 1);
            assertTrue(java.util.Arrays.equals(
                    stableJar,
                    Files.readAllBytes(snapshotJar)));
        } finally {
            release.countDown();
            application.join(TimeUnit.SECONDS.toMillis(10));
        }
        assertTrue(!application.isAlive());
        assertEquals(null, failure.get());
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), content);
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path member = tempDir.resolve(path);
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = %s
                %s""".formatted(name, currentJavaMajorVersion(), extraToml));
    }

    private void source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(
                    java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
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
