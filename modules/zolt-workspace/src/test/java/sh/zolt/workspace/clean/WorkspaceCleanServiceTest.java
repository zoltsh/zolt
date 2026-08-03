package sh.zolt.workspace.clean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.CleanException;
import sh.zolt.workspace.WorkspaceConfigException;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceCleanServiceTest {
    @TempDir
    private Path tempDir;

    private final WorkspaceCleanService service = new WorkspaceCleanService();

    @Test
    void cleansSelectedWorkspaceMembersAndDependenciesInBuildOrder() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api", "modules/core", "apps/worker"]
                """);
        member("modules/core", "core", "");
        member("apps/api", "api", """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        member("apps/worker", "worker", "");
        output("modules/core/target/classes/Core.class");
        output("apps/api/target/classes/Api.class");
        output("apps/worker/target/classes/Worker.class");

        WorkspaceCleanResult result = service.clean(
                tempDir,
                new WorkspaceSelectionRequest(false, List.of("apps/api")));

        assertEquals(List.of("modules/core", "apps/api"), result.selection().includedMembers());
        assertEquals(List.of("modules/core", "apps/api"), result.members().stream()
                .map(WorkspaceCleanResult.MemberCleanResult::member)
                .toList());
        assertEquals(2, result.deletedCount());
        assertFalse(Files.exists(tempDir.resolve("modules/core/target")));
        assertFalse(Files.exists(tempDir.resolve("apps/api/target")));
        assertTrue(Files.exists(tempDir.resolve("apps/worker/target/classes/Worker.class")));
    }

    @Test
    void cleansAllWorkspaceMembersWhenAllIsRequested() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["modules/core", "apps/api"]
                """);
        member("modules/core", "core", "");
        member("apps/api", "api", "");
        output("modules/core/target/classes/Core.class");
        output("apps/api/target/classes/Api.class");

        WorkspaceCleanResult result = service.clean(
                tempDir,
                new WorkspaceSelectionRequest(true, List.of()));

        assertEquals(List.of("modules/core", "apps/api"), result.selection().includedMembers());
        assertEquals(2, result.deletedCount());
        assertFalse(Files.exists(tempDir.resolve("modules/core/target")));
        assertFalse(Files.exists(tempDir.resolve("apps/api/target")));
    }

    @Test
    void doesNotRequireWorkspaceLockfile() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", "");
        output("apps/api/target/classes/Api.class");

        WorkspaceCleanResult result = service.clean(tempDir, WorkspaceSelectionRequest.defaults());

        assertEquals(1, result.deletedCount());
        assertFalse(Files.exists(tempDir.resolve("zolt.lock")));
        assertFalse(Files.exists(tempDir.resolve("apps/api/target")));
    }

    @Test
    void waitsForActiveWorkspaceMutationBeforeDeletingOutputs()
            throws Exception {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", "");
        Path output = output("apps/api/target/classes/Api.class");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread clean = Thread.ofPlatform().unstarted(() -> {
            try {
                service.clean(tempDir, WorkspaceSelectionRequest.defaults());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                finished.countDown();
            }
        });

        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(tempDir)) {
            clean.start();
            assertFalse(
                    finished.await(250, TimeUnit.MILLISECONDS),
                    "Clean did not wait for the active workspace mutation.");
            assertTrue(Files.isRegularFile(output));
        }
        clean.join(TimeUnit.SECONDS.toMillis(2));

        assertFalse(clean.isAlive());
        assertNull(failure.get());
        assertFalse(Files.exists(tempDir.resolve("apps/api/target")));
    }

    @Test
    void cleanCannotSlipBetweenBuildAndTestCompilation()
            throws Exception {
        assertCleanCannotSlipBetweenCommandPhases();
    }

    @Test
    void cleanCannotSlipBetweenBuildAndPackaging()
            throws Exception {
        assertCleanCannotSlipBetweenCommandPhases();
    }

    @Test
    void reportsMissingWorkspaceConfigWithCleanNextStep() {
        WorkspaceConfigException exception = assertThrows(
                WorkspaceConfigException.class,
                () -> service.clean(tempDir, WorkspaceSelectionRequest.defaults()));

        assertEquals(
                "Could not find workspace config. Run `zolt clean --workspace` from a workspace directory or add zolt.toml with [workspace].",
                exception.getMessage());
    }

    @Test
    void preservesMemberAndGlobalCaches() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", "");
        output("apps/api/target/classes/Api.class");
        output("apps/api/.zolt/cache/artifact.jar");
        output(".zolt/cache/artifact.jar");
        output("zolt.lock");
        output("apps/api/src/main/java/com/acme/Api.java");

        service.clean(tempDir, WorkspaceSelectionRequest.defaults());

        assertFalse(Files.exists(tempDir.resolve("apps/api/target")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/.zolt/cache/artifact.jar")));
        assertTrue(Files.exists(tempDir.resolve(".zolt/cache/artifact.jar")));
        assertTrue(Files.exists(tempDir.resolve("zolt-workspace.toml")));
        assertTrue(Files.exists(tempDir.resolve("zolt.lock")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/zolt.toml")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/src/main/java/com/acme/Api.java")));
    }

    @Test
    void preservesMavenAndGradleOutputsWhenMemberUsesIsolatedOutputRoot() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", """

                [build]
                outputRoot = ".zolt/build"
                source = "src/main/java"
                test = "src/test/java"
                output = ".zolt/build/classes"
                testOutput = ".zolt/build/test-classes"
                """);
        output("apps/api/.zolt/build/classes/Api.class");
        output("apps/api/target/classes/MavenApi.class");
        output("apps/api/build/classes/java/main/GradleApi.class");

        WorkspaceCleanResult result = service.clean(tempDir, WorkspaceSelectionRequest.defaults());

        assertEquals(1, result.deletedCount());
        assertFalse(Files.exists(tempDir.resolve("apps/api/.zolt/build")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/classes/MavenApi.class")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/build/classes/java/main/GradleApi.class")));
    }

    @Test
    void preservesExternallyOwnedGeneratedRootsAndDeletesCleanOwnedGeneratedRoots() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", """

                [generated.main.external]
                kind = "declared-root"
                language = "java"
                output = "target/generated/sources/external"
                inputs = ["src/main/openapi/external.yaml"]
                required = false
                clean = false

                [generated.main.owned]
                kind = "declared-root"
                language = "java"
                output = "target/generated/sources/owned"
                inputs = ["src/main/openapi/owned.yaml"]
                required = false
                clean = true
                """);
        output("apps/api/target/classes/Api.class");
        output("apps/api/target/test-classes/ApiTest.class");
        output("apps/api/target/generated/sources/external/External.java");
        output("apps/api/target/generated/sources/owned/Owned.java");

        service.clean(tempDir, WorkspaceSelectionRequest.defaults());

        assertFalse(Files.exists(tempDir.resolve("apps/api/target/classes")));
        assertFalse(Files.exists(tempDir.resolve("apps/api/target/test-classes")));
        assertTrue(Files.exists(tempDir.resolve("apps/api/target/generated/sources/external/External.java")));
        assertFalse(Files.exists(tempDir.resolve("apps/api/target/generated/sources/owned")));
    }

    @Test
    void removesFrameworkOutputsOnlyForMembersThatDeclareFrameworkSettings() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/quarkus", "apps/plain", "apps/spring"]
                """);
        member("apps/quarkus", "quarkus", nonTargetBuildSection() + """

                [framework.quarkus]
                enabled = true
                package = "fast-jar"
                """);
        member("apps/plain", "plain", nonTargetBuildSection());
        member("apps/spring", "spring", nonTargetBuildSection() + """

                [framework.springBoot.native]
                enabled = true
                """);
        output("apps/quarkus/out/main/Api.class");
        output("apps/quarkus/target/quarkus/zolt-augmentation.properties");
        output("apps/quarkus/target/quarkus-app/quarkus-run.jar");
        output("apps/plain/out/main/Plain.class");
        output("apps/plain/target/quarkus/zolt-augmentation.properties");
        output("apps/plain/target/spring-aot/main/classes/Plain__BeanDefinitions.class");
        output("apps/spring/out/main/Spring.class");
        output("apps/spring/target/spring-aot/main/classes/Spring__BeanDefinitions.class");

        service.clean(tempDir, new WorkspaceSelectionRequest(true, List.of()));

        assertFalse(Files.exists(tempDir.resolve("apps/quarkus/target/quarkus")));
        assertFalse(Files.exists(tempDir.resolve("apps/quarkus/target/quarkus-app")));
        assertTrue(Files.exists(tempDir.resolve("apps/plain/target/quarkus/zolt-augmentation.properties")));
        assertTrue(Files.exists(tempDir.resolve("apps/plain/target/spring-aot/main/classes/Plain__BeanDefinitions.class")));
        assertFalse(Files.exists(tempDir.resolve("apps/spring/target/spring-aot")));
    }

    @Test
    void addsMemberContextToCleanFailures() throws IOException {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", """

                [build]
                output = "../outside/classes"
                testOutput = "target/test-classes"
                """);

        CleanException exception = assertThrows(
                CleanException.class,
                () -> service.clean(tempDir, WorkspaceSelectionRequest.defaults()));

        assertTrue(exception.getMessage().contains("Workspace member `apps/api` could not be cleaned."));
        assertTrue(exception.getMessage().contains("[build].output"));
        assertTrue(exception.getMessage().contains("../outside/classes"));
        assertTrue(Files.exists(tempDir.resolve("zolt-workspace.toml")));
    }

    private void workspace(String content) throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), content);
    }

    private void assertCleanCannotSlipBetweenCommandPhases()
            throws Exception {
        workspace("""
                [workspace]
                name = "acme-platform"
                members = ["apps/api"]
                """);
        member("apps/api", "api", "");
        Path output = output("apps/api/target/classes/Api.class");
        CountDownLatch betweenPhases = new CountDownLatch(1);
        CountDownLatch continueCommand = new CountDownLatch(1);
        CountDownLatch commandFinished = new CountDownLatch(1);
        AtomicReference<Throwable> commandFailure = new AtomicReference<>();
        Thread command = Thread.ofPlatform().start(() -> {
            try (WorkspaceMutationLock commandLease =
                    WorkspaceMutationLock.acquire(tempDir)) {
                try (WorkspaceMutationLock ignored =
                        WorkspaceMutationLock.acquire(tempDir)) {
                    assertTrue(Files.isRegularFile(output));
                }
                betweenPhases.countDown();
                assertTrue(continueCommand.await(2, TimeUnit.SECONDS));
                try (WorkspaceMutationLock ignored =
                        WorkspaceMutationLock.acquire(tempDir)) {
                    assertTrue(Files.isRegularFile(output));
                }
            } catch (Throwable throwable) {
                commandFailure.set(throwable);
            } finally {
                commandFinished.countDown();
            }
        });
        assertTrue(betweenPhases.await(2, TimeUnit.SECONDS));

        CountDownLatch cleanFinished = new CountDownLatch(1);
        AtomicReference<Throwable> cleanFailure = new AtomicReference<>();
        Thread clean = Thread.ofPlatform().start(() -> {
            try {
                service.clean(tempDir, WorkspaceSelectionRequest.defaults());
            } catch (Throwable throwable) {
                cleanFailure.set(throwable);
            } finally {
                cleanFinished.countDown();
            }
        });
        assertFalse(
                cleanFinished.await(250, TimeUnit.MILLISECONDS),
                "Clean slipped between command phases.");
        assertTrue(Files.isRegularFile(output));

        continueCommand.countDown();
        assertTrue(commandFinished.await(2, TimeUnit.SECONDS));
        assertTrue(cleanFinished.await(2, TimeUnit.SECONDS));
        command.join(TimeUnit.SECONDS.toMillis(2));
        clean.join(TimeUnit.SECONDS.toMillis(2));

        assertNull(commandFailure.get());
        assertNull(cleanFailure.get());
        assertFalse(Files.exists(tempDir.resolve("apps/api/target")));
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path directory = tempDir.resolve(path);
        Files.createDirectories(directory);
        String buildSection = extraToml.contains("[build]")
                ? extraToml
                : """
                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "target/classes"
                testOutput = "target/test-classes"
                %s
                """.formatted(extraToml);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "%s"

                [repositories]
                central = "https://repo.maven.apache.org/maven2"

                %s
                """.formatted(name, currentJavaMajorVersion(), buildSection));
    }

    private Path output(String path) throws IOException {
        Path output = tempDir.resolve(path);
        Files.createDirectories(output.getParent());
        Files.writeString(output, "output");
        return output;
    }

    private static String nonTargetBuildSection() {
        return """

                [build]
                source = "src/main/java"
                test = "src/test/java"
                output = "out/main"
                testOutput = "out/test"
                """;
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
