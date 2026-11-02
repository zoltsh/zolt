package sh.zolt.toolchain.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.sun.net.httpserver.HttpServer;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.net.NetworkTransport;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.ToolchainSyncService;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
final class JavaToolchainInstallConcurrencyTest {
    @TempDir
    private Path tempDir;
    private final List<Process> children = new ArrayList<>();

    @AfterEach
    void stopChildren() throws InterruptedException {
        for (Process child : children) {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
            child.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void separateProjectsSerializeTheSameJdkAcrossCanonicalStoreAliases() throws Exception {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), ".", true);
        LockedJavaToolchain locked = ToolchainInstallFixtures.locked(archive, "21", ".");
        Path firstProject = ToolchainInstallFixtures.project(tempDir.resolve("first"), locked);
        Path secondProject = ToolchainInstallFixtures.project(tempDir.resolve("second"), locked);
        Path store = tempDir.resolve("store");
        try (DownloadServer server = new DownloadServer(Files.readAllBytes(archive))) {
            Process first = start(firstProject, store, server);
            assertTrue(server.firstRequested.await(10, TimeUnit.SECONDS));
            Path alias = tempDir.resolve("alias");
            Files.createSymbolicLink(alias, store);
            Process second = start(secondProject, alias, server);

            assertFalse(server.additionalRequested.await(1, TimeUnit.SECONDS), "second installer must wait before download");
            assertTrue(second.isAlive(), "second installer must wait for the completed first installation");
            server.releaseFirst.countDown();
            assertSuccess(first, firstProject);
            assertSuccess(second, secondProject);
            assertEquals(1, server.requests.get());
            assertTrue(Files.readString(firstProject.resolve("result.log")).contains("downloaded=true"));
            assertTrue(Files.readString(secondProject.resolve("result.log")).contains("downloaded=false"));
            assertTrue(new ToolchainStore(store).installed(locked));
            try (var paths = Files.list(new ToolchainStore(store).installRoot(locked).getParent())) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".jdk-attempt-")));
            }
        }
    }

    @Test
    void threadsInOneJvmShareTheSameInstallationLease() throws Exception {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), ".", true);
        LockedJavaToolchain locked = ToolchainInstallFixtures.locked(archive, "21", ".");
        Path firstProject = ToolchainInstallFixtures.project(tempDir.resolve("first"), locked);
        Path secondProject = ToolchainInstallFixtures.project(tempDir.resolve("second"), locked);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("store"));
        try (DownloadServer server = new DownloadServer(Files.readAllBytes(archive));
                var executor = Executors.newFixedThreadPool(2)) {
            try {
                var service = ToolchainSyncService.withNetwork(NetworkTransport.direct(), ToolchainDownloadMirror.of(server.mirror()));
                var first = executor.submit(() -> service.sync(firstProject, null, HostPlatform.parse("windows-x64"), store));
                assertTrue(server.firstRequested.await(10, TimeUnit.SECONDS));
                CountDownLatch entered = new CountDownLatch(1);
                var second = executor.submit(() -> {
                    entered.countDown();
                    return service.sync(secondProject, null, HostPlatform.parse("windows-x64"), store);
                });
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertFalse(server.additionalRequested.await(1, TimeUnit.SECONDS));
                assertFalse(second.isDone(), "same-JVM contender must wait without OverlappingFileLockException");
                server.releaseFirst.countDown();
                assertTrue(first.get(20, TimeUnit.SECONDS).downloaded());
                assertFalse(second.get(20, TimeUnit.SECONDS).downloaded());
                assertEquals(1, server.requests.get());
            } finally {
                server.releaseFirst.countDown();
            }
        }
    }

    @Test
    void distinctJdkIdentitiesCanInstallWhileAnotherDownloadIsBlocked() throws Exception {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), ".", true);
        LockedJavaToolchain firstLock = ToolchainInstallFixtures.locked(archive, "21", ".");
        LockedJavaToolchain secondLock = ToolchainInstallFixtures.locked(archive, "22", ".");
        Path firstProject = ToolchainInstallFixtures.project(tempDir.resolve("first"), firstLock);
        Path secondProject = ToolchainInstallFixtures.project(tempDir.resolve("second"), secondLock);
        Path store = tempDir.resolve("store");
        try (DownloadServer server = new DownloadServer(Files.readAllBytes(archive))) {
            Process first = start(firstProject, store, server);
            assertTrue(server.firstRequested.await(10, TimeUnit.SECONDS));
            Process second = start(secondProject, store, server);
            assertSuccess(second, secondProject);
            assertTrue(first.isAlive());
            assertTrue(new ToolchainStore(store).installed(secondLock));
            server.releaseFirst.countDown();
            assertSuccess(first, firstProject);
            assertTrue(new ToolchainStore(store).installed(firstLock));
        }
    }

    @Test
    void killedInstallerReleasesKernelLeaseAndCannotPoisonTheNextAttempt() throws Exception {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), ".", true);
        LockedJavaToolchain locked = ToolchainInstallFixtures.locked(archive, "21", ".");
        Path firstProject = ToolchainInstallFixtures.project(tempDir.resolve("first"), locked);
        Path secondProject = ToolchainInstallFixtures.project(tempDir.resolve("second"), locked);
        Path store = tempDir.resolve("store");
        try (DownloadServer server = new DownloadServer(Files.readAllBytes(archive))) {
            Process first = start(firstProject, store, server);
            assertTrue(server.firstRequested.await(10, TimeUnit.SECONDS));
            first.destroyForcibly();
            assertTrue(first.waitFor(10, TimeUnit.SECONDS));
            Process second = start(secondProject, store, server);
            assertSuccess(second, secondProject);
            assertTrue(new ToolchainStore(store).installed(locked));
            assertEquals(2, server.requests.get());
        }
    }

    private Process start(Path project, Path store, DownloadServer server) throws Exception {
        try (var watcher = project.getFileSystem().newWatchService()) {
            project.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djava.io.tmpdir=" + tempDir, "-cp", workerClasspath(), Worker.class.getName(),
                    project.toString(), store.toString(), server.mirror())
                    .redirectErrorStream(true).redirectOutput(project.resolve("result.log").toFile()).start();
            children.add(process);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(project.resolve("started"))) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "installer child did not start");
                var event = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                assertTrue(event != null, "installer child did not start");
                event.pollEvents();
                event.reset();
            }
            return process;
        }
    }

    private static void assertSuccess(Process process, Path project) throws Exception {
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "installer did not finish");
        assertEquals(0, process.exitValue(), Files.readString(project.resolve("result.log")));
    }

    private static String workerClasspath() throws Exception {
        var entries = new LinkedHashSet<String>(List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
        for (Class<?> type : List.of(Worker.class, ToolchainSyncService.class, JavaToolchainRequest.class,
                ZoltLockfileReader.class, org.tomlj.Toml.class, org.antlr.v4.runtime.Parser.class, JsonFactory.class)) {
            entries.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        return String.join(java.io.File.pathSeparator, entries);
    }

    public static final class Worker {
        public static void main(String[] args) throws Exception {
            Path project = Path.of(args[0]);
            Files.writeString(project.resolve("started"), "ready");
            var result = ToolchainSyncService.withNetwork(NetworkTransport.direct(), ToolchainDownloadMirror.of(args[2]))
                    .sync(project, null, HostPlatform.parse("windows-x64"), new ToolchainStore(Path.of(args[1])));
            System.out.println("installed=" + result.installed() + "; downloaded=" + result.downloaded());
        }
    }

    private static final class DownloadServer implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        private final CountDownLatch firstRequested = new CountDownLatch(1);
        private final CountDownLatch additionalRequested = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();

        private DownloadServer(byte[] archive) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jdk.zip", exchange -> {
                try {
                    if (requests.incrementAndGet() == 1) {
                        firstRequested.countDown();
                        if (!releaseFirst.await(45, TimeUnit.SECONDS)) {
                            throw new IOException("test did not release first download");
                        }
                    } else {
                        additionalRequested.countDown();
                    }
                    exchange.sendResponseHeaders(200, archive.length);
                    exchange.getResponseBody().write(archive);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } catch (IOException ignored) {
                    // Killing the first installer intentionally closes its HTTP connection.
                } finally {
                    exchange.close();
                }
            });
            server.setExecutor(executor);
            server.start();
        }

        private String mirror() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            releaseFirst.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
