package sh.zolt.release.update;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import sh.zolt.release.ReleaseTarget;

final class NativeInstallationTransactionTest extends NativeUpdateServiceTestCase {
    @Test
    void independentUpdatesInstallAndPruneWaitForTheActiveTransactionAndRecheckState() throws Exception {
        InstalledFixture installed = install("1.0.0");
        Path archive = gatedArchive();
        Path channel = writeChannel("stable", "1.0.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");
        Path gate = createGate("gate");
        Process first = probe("update", installed, channel, gate, null);
        List<Process> processes = new ArrayList<>(List.of(first));
        try {
            awaitEntered(gate);
            try (var attempts = Files.list(installed.installRoot().resolve("versions"))) {
                Path attempt = attempts.filter(path -> path.getFileName().toString().startsWith(".install-attempt-"))
                        .findFirst().orElseThrow();
                assertEquals(Files.getFileStore(installed.installRoot()), Files.getFileStore(attempt));
                assertTrue(Files.isExecutable(attempt.resolve("extract/zolt-1.0.1-linux-x64/bin/zolt")));
            }
            processes.add(probe("update", installed, channel, null, null));
            processes.add(probe("install", installed, writeReleaseIndex("1.0.1", archive), null, null));
            processes.add(probe("prune", installed, channel, null, null));
            for (Process process : processes) assertFalse(process.waitFor(200, TimeUnit.MILLISECONDS));
            releaseGate(gate);
            for (Process process : processes) assertSucceeded(process);
            assertEquals("1.0.1", version(installed.binLink()));
            assertEquals("1.0.0", Files.readString(installed.installRoot().resolve("previous-version")).strip());
            assertEquals("1.0.0", version(installed.installRoot().resolve("versions/1.0.0/bin/zolt")));
            assertFalse(Files.exists(installed.installRoot().resolve(".install-lock")));
            try (var directories = Files.list(installed.installRoot().resolve("versions"))) {
                assertFalse(directories.anyMatch(path -> path.getFileName().toString().startsWith(".install-attempt-")));
            }
        } finally {
            for (Process process : processes) stop(process);
        }
    }

    @Test
    void canonicalRootLeaseAlsoBlocksUseRollbackAndPrune() throws Exception {
        InstalledFixture installed = install("1.0.0");
        writeFakeZolt(installed.installRoot().resolve("versions/1.0.1/bin/zolt"), "1.0.1");
        Files.writeString(installed.installRoot().resolve("previous-version"), "1.0.1\n");
        Path alias = tempDir.resolve("alias");
        Files.createSymbolicLink(alias, installed.installRoot());
        InstalledFixture throughAlias = new InstalledFixture(alias, alias.resolve("bin/zolt"));
        for (String operation : List.of("use", "rollback", "prune")) {
            Process process;
            try (NativeInstallLease ignored = NativeInstallLease.acquire(installed.installRoot())) {
                process = probe(operation, throughAlias, tempDir, null, null);
                assertFalse(process.waitFor(300, TimeUnit.MILLISECONDS), operation + " bypassed installation lease");
            }
            try { assertSucceeded(process); } finally { stop(process); }
            assertTrue(Files.isExecutable(installed.binLink()));
        }
    }

    @Test
    void shellAndNativeInstallerShareTheCanonicalLeaseAndKeepCompletedVersionsStable() throws Exception {
        InstalledFixture installed = install("1.0.0");
        Path archive = gatedArchive();
        Path channel = writeChannel("stable", "1.0.1", "linux-x64", archive, archive.getFileName().toString(), sha256(archive));
        Path gate = createGate("shell-gate");
        Path alias = Files.createSymbolicLink(tempDir.resolve("shell-alias"), installed.installRoot());
        Path repository = Path.of("").toAbsolutePath();
        while (repository != null && !Files.isRegularFile(repository.resolve("scripts/install-zolt"))) repository = repository.getParent();
        assertNotNull(repository, "source installer script not found");
        ProcessBuilder shell = new ProcessBuilder("sh", repository.resolve("scripts/install-zolt").toString())
                .redirectErrorStream(true);
        shell.environment().putAll(java.util.Map.of("ZOLT_INSTALL_ROOT", alias.toString(),
                "ZOLT_INSTALL_CHANNEL", "stable", "ZOLT_INSTALL_CHANNEL_URL", channel.toUri().toString(),
                "ZOLT_INSTALL_TARGET", "linux-x64", "ZOLT_INSTALL_TEST_GATE", gate.toString()));
        Process shellProcess;
        try (NativeInstallLease ignored = NativeInstallLease.acquire(installed.installRoot())) {
            shellProcess = shell.start();
            assertFalse(shellProcess.waitFor(300, TimeUnit.MILLISECONDS));
            assertFalse(Files.exists(gate.resolve("entered")), "shell bypassed native lease");
        }
        Process nativeProcess = null;
        try {
            awaitEntered(gate);
            nativeProcess = probe("update", installed, channel, null, null);
            assertFalse(nativeProcess.waitFor(300, TimeUnit.MILLISECONDS), "native update bypassed shell lease");
            releaseGate(gate);
            assertSucceeded(shellProcess);
            assertSucceeded(nativeProcess);
            assertEquals("1.0.1", version(installed.binLink()));
            Path completed = installed.installRoot().resolve("versions/1.0.1");
            Object fileKey = Files.readAttributes(completed, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            Process reinstall = shell.start();
            try { assertSucceeded(reinstall); } finally { stop(reinstall); }
            assertEquals(fileKey, Files.readAttributes(completed, java.nio.file.attribute.BasicFileAttributes.class).fileKey());
            assertEquals("1.0.0", Files.readString(installed.installRoot().resolve("previous-version")).strip());
        } finally {
            stop(shellProcess);
            if (nativeProcess != null) stop(nativeProcess);
        }
    }

    @Test
    void shellRejectsDirectoryValuedLauncherWithoutFollowingItsTarget() throws Exception {
        InstalledFixture installed = install("1.0.0");
        Path outside = Files.createDirectory(tempDir.resolve("outside-directory"));
        Path marker = Files.writeString(outside.resolve("preserve.txt"), "untouched");
        Files.delete(installed.binLink());
        Files.createSymbolicLink(installed.binLink(), outside);
        Path repository = Path.of("").toAbsolutePath();
        while (repository != null && !Files.isRegularFile(repository.resolve("scripts/install-zolt"))) repository = repository.getParent();
        assertNotNull(repository, "source installer script not found");
        ProcessBuilder shell = new ProcessBuilder("sh", repository.resolve("scripts/install-zolt").toString())
                .redirectErrorStream(true);
        shell.environment().putAll(java.util.Map.of("ZOLT_INSTALL_ROOT", installed.installRoot().toString(),
                "ZOLT_INSTALL_CHANNEL", "stable", "ZOLT_INSTALL_CHANNEL_URL", tempDir.resolve("missing-channel.json").toUri().toString(),
                "ZOLT_INSTALL_TARGET", "linux-x64"));
        Process process = shell.start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertNotEquals(0, process.exitValue());
            assertTrue(new String(process.getInputStream().readAllBytes()).contains("launcher must point to an executable file"));
            assertEquals(outside, Files.readSymbolicLink(installed.binLink()));
            assertEquals("untouched", Files.readString(marker));
            try (var contents = Files.list(outside)) { assertEquals(List.of(marker), contents.toList()); }
            assertFalse(Files.exists(installed.installRoot().resolve(".install-lock")));
        } finally { stop(process); }
    }

    @Test
    void forcedOwnerExitPreservesCurrentAndNeverStealsTheOrphanLease() throws Exception {
        InstalledFixture installed = install("1.0.0");
        Path archive = gatedArchive();
        Path channel = writeChannel("stable", "1.0.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");
        Path gate = createGate("crash-gate");
        Process process = probe("update", installed, channel, gate, null);
        try {
            awaitEntered(gate);
            stop(process);
            assertEquals("1.0.0", version(installed.binLink()));
            NativeUpdateException failure = assertThrows(NativeUpdateException.class,
                    () -> NativeInstallLease.acquire(installed.installRoot(), Duration.ofMillis(100)));
            assertTrue(failure.getMessage().contains("owner"), failure.getMessage());
            assertTrue(failure.getMessage().contains("confirm no installer is running"), failure.getMessage());
            assertTrue(Files.isRegularFile(installed.installRoot().resolve(".install-lock/owner")));
            assertEquals("1.0.0", version(installed.binLink()));
        } finally { stop(process); }
    }

    @Test
    void systemTemporaryDirectoryOnAnotherFileStoreDoesNotMoveTheCandidateAcrossStores() throws Exception {
        Path secondary = secondaryFileStore();
        assumeTrue(secondary != null, "No distinct readable FileStore available");
        InstalledFixture installed = install("1.0.0");
        assertNotEquals(Files.getFileStore(installed.installRoot()), Files.getFileStore(secondary));
        System.out.println("cross-filesystem install root=" + Files.getFileStore(installed.installRoot())
                + "; java.io.tmpdir=" + Files.getFileStore(secondary));
        Path archive = archive("1.0.1", "linux-x64", "1.0.1");
        Path channel = writeChannel("stable", "1.0.1", "linux-x64", archive, archive.getFileName().toString(), "sidecar");
        Process process = probe("update", installed, channel, null, secondary);
        try { assertSucceeded(process); } finally { stop(process); }
        assertEquals("1.0.1", version(installed.binLink()));
        assertEquals("1.0.0", version(installed.installRoot().resolve("versions/1.0.0/bin/zolt")));
        Path badArchive = archive("1.0.2", "linux-x64", "wrong-version");
        Path badChannel = writeChannel("stable", "1.0.2", "linux-x64", badArchive, badArchive.getFileName().toString(), "sidecar");
        Process failure = probe("update", installed, badChannel, null, secondary);
        try {
            assertTrue(failure.waitFor(20, TimeUnit.SECONDS));
            assertNotEquals(0, failure.exitValue());
        } finally { stop(failure); }
        assertEquals("1.0.1", version(installed.binLink()));
        assertFalse(Files.exists(installed.installRoot().resolve("versions/1.0.2")));
        Process failedInstall = probe("install", installed, writeReleaseIndex("1.0.2", badArchive), null, secondary);
        try {
            assertTrue(failedInstall.waitFor(20, TimeUnit.SECONDS));
            assertNotEquals(0, failedInstall.exitValue());
        } finally { stop(failedInstall); }
        assertFalse(Files.exists(installed.installRoot().resolve("versions/1.0.2")));
        Path installArchive = archive("1.0.3", "linux-x64", "1.0.3");
        Process install = probe("install", installed, writeReleaseIndex("1.0.3", installArchive), null, secondary);
        try { assertSucceeded(install); } finally { stop(install); }
        assertEquals("1.0.3", version(installed.installRoot().resolve("versions/1.0.3/bin/zolt")));
        assertEquals("1.0.1", version(installed.binLink()));
    }

    private Path writeReleaseIndex(String version, Path archive) throws Exception {
        Path index = tempDir.resolve("index-" + version + ".json");
        Files.writeString(index, """
                {"schemaVersion":1,"channel":"stable","updatedAt":"2026-07-07T00:00:00Z","versions":[
                  {"version":"%s","commit":"0123456789abcdef0123456789abcdef01234567","createdAt":"2026-07-07T00:00:00Z",
                   "artifacts":[{"target":"linux-x64","archive":"%s","archiveUrl":"%s",
                     "checksumUrl":"%s","format":"tar.gz","binaryName":"zolt"}]}]}
                """.formatted(version, archive.getFileName(), archive.toUri(),
                        archive.resolveSibling(archive.getFileName() + ".sha256").toUri()));
        return index;
    }

    private Path secondaryFileStore() throws Exception {
        String configured = System.getenv("ZOLT_TEST_OTHER_FILESTORE");
        for (Path candidate : configured == null
                ? List.of(Path.of("/dev/shm"), Path.of("/private/tmp")) : List.of(Path.of(configured))) {
            if (Files.isDirectory(candidate) && !Files.getFileStore(candidate).equals(Files.getFileStore(tempDir))) return candidate;
        }
        return null;
    }

    private Path gatedArchive() throws Exception {
        Path archive = archive("1.0.1", "linux-x64", "1.0.1");
        Path binary = tempDir.resolve("package/zolt-1.0.1-linux-x64/bin/zolt");
        Files.writeString(binary, """
                #!/bin/sh
                if [ -n "${ZOLT_INSTALL_TEST_GATE:-}" ] && [ ! -f "$ZOLT_INSTALL_TEST_GATE/released" ]; then
                  touch "$ZOLT_INSTALL_TEST_GATE/entered"
                  read -r release < "$ZOLT_INSTALL_TEST_GATE/release"
                fi
                echo 1.0.1
                """);
        Process pack = new ProcessBuilder("tar", "-C", tempDir.resolve("package").toString(),
                "-czf", archive.toString(), "zolt-1.0.1-linux-x64").start();
        assertEquals(0, pack.waitFor());
        Files.writeString(archive.resolveSibling(archive.getFileName() + ".sha256"), sha256(archive) + "  " + archive.getFileName() + "\n");
        return archive;
    }

    private Process probe(String operation, InstalledFixture installed, Path channel, Path gate, Path systemTemp) throws Exception {
        String classpath = String.join(File.pathSeparator, location(Probe.class), location(NativeUpdateService.class),
                location(sh.zolt.dependency.VersionComparator.class));
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-XX:-UsePerfData", "-cp", classpath));
        if (systemTemp != null) command.add("-Djava.io.tmpdir=" + systemTemp);
        command.addAll(List.of(Probe.class.getName(), operation, installed.installRoot().toString(), channel.toString()));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if (gate != null) builder.environment().put("ZOLT_INSTALL_TEST_GATE", gate.toString());
        return builder.start();
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private Path createGate(String name) throws Exception {
        Path gate = Files.createDirectory(tempDir.resolve(name));
        Process fifo = new ProcessBuilder("mkfifo", gate.resolve("release").toString()).start();
        try { assertSucceeded(fifo); } finally { stop(fifo); }
        return gate;
    }

    private static void releaseGate(Path gate) throws Exception {
        Files.createFile(gate.resolve("released"));
        Process writer = new ProcessBuilder("sh", "-c", "printf '%s\\n' release > \"$1\"",
                "release-gate", gate.resolve("release").toString()).start();
        try { assertSucceeded(writer); } finally { stop(writer); }
    }

    private static void awaitEntered(Path gate) throws Exception {
        try (var watcher = gate.getFileSystem().newWatchService()) {
            gate.register(watcher, StandardWatchEventKinds.ENTRY_CREATE);
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (!Files.exists(gate.resolve("entered"))) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "candidate did not reach smoke gate");
                var event = watcher.poll(remaining, TimeUnit.NANOSECONDS);
                assertNotNull(event, "candidate did not reach smoke gate");
                event.pollEvents();
                assertTrue(event.reset(), "smoke gate directory became unavailable");
            }
        }
    }

    private static void assertSucceeded(Process process) throws Exception {
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "installation process timed out");
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }

    private static String version(Path binary) throws Exception {
        Process process = new ProcessBuilder(binary.toString(), "--version").redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        return new String(process.getInputStream().readAllBytes()).strip();
    }

    private static void stop(Process process) throws Exception {
        if (process.isAlive()) {
            process.descendants().forEach(child -> child.destroyForcibly());
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[1]);
            Path executable = root.resolve("bin/zolt").toRealPath();
            NativeUpdateTransport delegate = NativeUpdateTransport.standard();
            NativeUpdateTransport observed = new NativeUpdateTransport() {
                public byte[] downloadBytes(java.net.URI uri, int maxBytes, String description) throws java.io.IOException {
                    return delegate.downloadBytes(uri, maxBytes, description);
                }
                public void download(java.net.URI uri, Path output) throws java.io.IOException {
                    Path versions = root.toRealPath().resolve("versions");
                    if (!output.startsWith(versions) || !Files.getFileStore(output.getParent()).equals(Files.getFileStore(versions))) {
                        throw new AssertionError("download escaped destination FileStore: " + output);
                    }
                    delegate.download(uri, output);
                    System.out.println("destination-local-download=" + output);
                }
            };
            switch (args[0]) {
                case "update" -> {
                    var updater = new NativeUpdateService(new sh.zolt.release.channel.ReleaseChannelManifestValidator(),
                            sh.zolt.release.signing.ReleaseSignatureVerifier.bundled(), observed);
                    System.out.println(updater.update(new NativeUpdateRequest(root,
                            executable, Path.of(args[2]).toUri(), ReleaseTarget.LINUX_X64, null)));
                }
                case "install" -> {
                    var indexes = new NativeReleaseIndexService();
                    var uri = Path.of(args[2]).toUri();
                    String version = indexes.read(new NativeReleaseListRequest(uri)).versions().getFirst().version();
                    var installer = new NativeVersionInstallService(indexes, observed);
                    System.out.println(installer.install(new NativeVersionInstallRequest(root, executable, uri,
                            version, ReleaseTarget.LINUX_X64, null)));
                }
                case "use" -> new NativeVersionService().use(new NativeVersionSwitchRequest(root, executable, "1.0.1"));
                case "rollback" -> new NativeVersionService().rollback(new NativeVersionListRequest(root, executable));
                case "prune" -> new NativeVersionService().prune(new NativeVersionPruneRequest(root, executable, 1, false));
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
