package sh.zolt.toolchain.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.net.NetworkTransport;
import sh.zolt.toolchain.ToolchainSyncService;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

final class JavaToolchainPathContainmentTest {
    @TempDir
    private Path tempDir;

    static Stream<Arguments> unsafeLockPaths() {
        return Stream.of("id", "resolved.version", "layout.javaHome", "layout.executables.java",
                "layout.executables.javac", "layout.executables.jar", "layout.executables.nativeImage")
                .flatMap(field -> Stream.of("../outside", "/outside", "C:/outside", "C:\\outside", "\\\\server\\share")
                        .map(value -> Arguments.of(field, value)));
    }

    @ParameterizedTest
    @MethodSource("unsafeLockPaths")
    void rejectsUnsafeRealLockfileBeforeSyncMutatesTheStore(String field, String value) throws IOException {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), ".", true);
        LockedJavaToolchain locked = ToolchainInstallFixtures.locked(archive, "21", ".");
        Path project = ToolchainInstallFixtures.project(tempDir.resolve("project"), locked);
        Path lockfile = project.resolve("zolt.lock");
        String existing = Files.readString(lockfile);
        String unsafeValue = value.equals("/outside") ? tempDir.resolve("outside").toString() : value;
        String assignment = field + " = \"" + unsafeValue.replace("\\", "\\\\") + "\"";
        String changed = existing.lines().map(line -> line.startsWith(field + " = ") ? assignment : line)
                .collect(java.util.stream.Collectors.joining("\n", "", "\n"));
        if (!existing.contains(field + " = ")) {
            changed += assignment + "\n";
        }
        Files.writeString(lockfile, changed);
        Path sentinel = Files.writeString(tempDir.resolve("outside-sentinel"), "preserve me");
        Path storeRoot = tempDir.resolve("store");
        ToolchainSyncService service = ToolchainSyncService.withNetwork(NetworkTransport.direct(),
                ToolchainDownloadMirror.of(archive.getParent().toUri().toString()));

        ActionableException error = assertThrows(ActionableException.class, () -> service.sync(
                project, null, HostPlatform.parse("windows-x64"), new ToolchainStore(storeRoot)));

        assertTrue(error.getMessage().contains("toolchain sync --refresh"), error.getMessage());
        assertEquals("preserve me", Files.readString(sentinel));
        assertEquals(changed, Files.readString(lockfile));
        assertFalse(Files.exists(storeRoot), "unsafe lock metadata must fail before store mutation");
    }

    @ParameterizedTest
    @ValueSource(strings = {"java", "java/temurin", "java/temurin/21", "java/temurin/21/windows-x64", "java/temurin/21/windows-x64/jdk"})
    void rejectsExistingEscapingSymlinkAncestorsBeforeDeletion(String ancestor) throws IOException {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), ".", true);
        LockedJavaToolchain locked = ToolchainInstallFixtures.locked(archive, "21", ".");
        Path root = tempDir.resolve("store");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(outside);
        Path link = root.resolve(ancestor);
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, outside);
        Path escaped = outside.resolve(Path.of(ancestor).relativize(Path.of("java/temurin/21/windows-x64/jdk")));
        Path oldStaging = escaped.resolveSibling("jdk.tmp");
        Files.createDirectories(oldStaging);
        Files.writeString(oldStaging.resolve("sentinel"), "preserve staging");
        Files.createDirectories(escaped);
        Files.writeString(escaped.resolve("sentinel"), "preserve installation");

        assertThrows(ActionableException.class, () -> ToolchainInstallFixtures.installer(archive)
                .install(locked, ToolchainInstallFixtures.artifact(locked), new ToolchainStore(root)));

        assertEquals("preserve staging", Files.readString(oldStaging.resolve("sentinel")));
        assertEquals("preserve installation", Files.readString(escaped.resolve("sentinel")));
        assertFalse(Files.exists(escaped.resolve("bin/java")));
    }

    @Test
    void rejectsEscapingInstalledExecutableSymlinks() throws IOException {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), ".", true);
        LockedJavaToolchain locked = ToolchainInstallFixtures.locked(archive, "21", ".");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("store"));
        Path bin = store.installRoot(locked).resolve("bin");
        Files.createDirectories(bin);
        Path external = Files.writeString(tempDir.resolve("outside-java"), "preserve me");
        Files.createSymbolicLink(bin.resolve("java"), external);

        assertThrows(ActionableException.class, () -> store.installed(locked));
        assertThrows(ActionableException.class, () -> ToolchainInstallFixtures.installer(archive)
                .install(locked, ToolchainInstallFixtures.artifact(locked), store));
        assertEquals("preserve me", Files.readString(external));
    }

    @Test
    void rejectsEscapingLeaseAndDownloadPaths() throws IOException {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), ".", true);
        LockedJavaToolchain locked = ToolchainInstallFixtures.locked(archive, "21", ".");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("store"));
        Path parent = store.installRoot(locked).getParent();
        Files.createDirectories(parent);
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("lock-sentinel"), "preserve me");
        Files.createSymbolicLink(parent.resolve(".jdk.install.lock"), sentinel);
        Files.createSymbolicLink(store.root().resolve("downloads"), outside);

        assertThrows(ActionableException.class, () -> ToolchainInstallFixtures.installer(archive)
                .install(locked, ToolchainInstallFixtures.artifact(locked), store));
        assertThrows(ActionableException.class, () -> store.downloadArchive(locked, "zip"));
        assertEquals("preserve me", Files.readString(sentinel));
        assertFalse(Files.exists(store.installRoot(locked)));
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "Contents/Home"})
    void preservesValidLayoutsAndCanonicalRootAliases(String home) throws Exception {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), home, true);
        LockedJavaToolchain locked = ToolchainInstallFixtures.locked(archive, "21", home);
        Path real = Files.createDirectory(tempDir.resolve("store"));
        Path alias = tempDir.resolve("store-alias");
        Files.createSymbolicLink(alias, real);
        ToolchainStore store = new ToolchainStore(alias);

        assertTrue(ToolchainInstallFixtures.installer(archive).install(locked, ToolchainInstallFixtures.artifact(locked), store));
        assertTrue(store.installed(locked));
        assertEquals(real.toRealPath(), store.root());
        Process process = new ProcessBuilder(store.java(locked).toString()).start();
        assertEquals("java-ok\n", new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(0, process.waitFor());
        assertFalse(ToolchainInstallFixtures.installer(archive).install(locked, ToolchainInstallFixtures.artifact(locked), store));
        assertNoAttempts(store.installRoot(locked).getParent());
    }

    @Test
    void invalidCandidatePreservesTheExistingPartialInstallation() throws IOException {
        Path archive = ToolchainInstallFixtures.archive(tempDir.resolve("mirror"), ".", false);
        LockedJavaToolchain locked = ToolchainInstallFixtures.locked(archive, "21", ".");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("store"));
        Path existing = store.installRoot(locked);
        Files.createDirectories(existing);
        Files.writeString(existing.resolve("sentinel"), "preserve prior state");

        assertThrows(ActionableException.class, () -> ToolchainInstallFixtures.installer(archive)
                .install(locked, ToolchainInstallFixtures.artifact(locked), store));

        assertEquals("preserve prior state", Files.readString(existing.resolve("sentinel")));
        assertNoAttempts(existing.getParent());
    }

    private static void assertNoAttempts(Path parent) throws IOException {
        try (var paths = Files.list(parent)) {
            assertEquals(List.of(), paths.filter(path -> path.getFileName().toString().startsWith(".jdk-attempt-")).toList());
        }
    }
}
