package sh.zolt.workspace.packaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.workspace.service.WorkspaceBuildPlan;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMutationLock;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import sh.zolt.workspace.service.WorkspacePlanTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WorkspaceRunPackageSnapshotLockTest {
    @TempDir
    private Path tempDir;

    @Test
    void stagedPackageSnapshotWaitsForWorkspaceLease() throws Exception {
        writeProject();
        WorkspaceRunPackageService service =
                new WorkspaceRunPackageService();
        Path cacheRoot = tempDir.resolve("cache");
        WorkspaceBuildPlan plan = service.planRunPackages(
                WorkspacePlanTarget.at(tempDir),
                cacheRoot,
                WorkspaceSelectionRequest.defaults());
        WorkspaceBuildResult buildResult =
                service.buildRunPackageInputs(plan, cacheRoot);
        WorkspacePackageResult packageResult =
                service.packageRunPackageInputs(
                        plan,
                        buildResult,
                        cacheRoot,
                        Optional.empty());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<WorkspaceRunPackageSnapshot> snapshot =
                new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker;

        try (WorkspaceMutationLock ignored =
                WorkspaceMutationLock.acquire(tempDir)) {
            worker = Thread.ofPlatform().start(() -> {
                started.countDown();
                try {
                    snapshot.set(service.snapshotRunPackages(
                            plan,
                            packageResult));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                } finally {
                    completed.countDown();
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(
                    completed.await(200, TimeUnit.MILLISECONDS),
                    "public staged package snapshot did not wait for the workspace lease");
        }

        assertTrue(completed.await(10, TimeUnit.SECONDS));
        worker.join();
        assertEquals(null, failure.get());
        try (WorkspaceRunPackageSnapshot ignored = snapshot.get()) {
            assertEquals(1, ignored.members().size());
        }
    }

    private void writeProject() throws IOException {
        Files.writeString(tempDir.resolve("zolt-workspace.toml"), """
                [workspace]
                name = "staged-package-run"
                members = ["apps/api"]
                """);
        Path member = tempDir.resolve("apps/api");
        Files.createDirectories(member);
        Files.writeString(member.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.acme"
                java = "%s"
                main = "com.acme.api.Api"
                """.formatted(currentJavaVersion()));
        Path source = member.resolve(
                "src/main/java/com/acme/api/Api.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.acme.api;

                public final class Api {
                    public static void main(String[] args) {
                    }
                }
                """);
    }

    private static String currentJavaVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0])
                ? parts[1]
                : parts[0];
    }
}
