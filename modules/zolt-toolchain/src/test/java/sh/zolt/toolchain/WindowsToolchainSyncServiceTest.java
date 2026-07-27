package sh.zolt.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.toolchain.catalog.BundledJavaToolchainCatalog;
import sh.zolt.toolchain.catalog.JavaToolchainArchiveFormat;
import sh.zolt.toolchain.catalog.JavaToolchainArtifact;
import sh.zolt.toolchain.catalog.JavaToolchainCatalog;
import sh.zolt.toolchain.install.JavaToolchainInstaller;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class WindowsToolchainSyncServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void syncAddsAndInstallsWindowsHostFromBundledMatrix() throws IOException {
        Path project = writeNativeProject();
        Path archive = fakeWindowsGraalArchive();
        HostPlatform windows = HostPlatform.parse("windows-x64");
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        ToolchainLockfileService lockfiles = new ToolchainLockfileService();
        ToolchainSyncService service = new ToolchainSyncService(
                new ToolchainConfigReader(),
                new LocalBundledCatalog(archive),
                lockfiles,
                new JavaToolchainInstaller());

        ToolchainSyncResult result = service.sync(project, null, windows, store);

        assertEquals(windows, result.locked().platform());
        assertTrue(result.installed());
        assertTrue(store.installed(result.locked()));
        assertTrue(lockfiles
                .findJava(project.resolve("zolt.lock"), result.locked().request(), windows)
                .isPresent());
        assertEquals(5, lockfiles.readJava(project.resolve("zolt.lock")).size());
    }

    private Path writeNativeProject() throws IOException {
        Path project = tempDir.resolve("windows-graal-sync");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "windows-graal-sync"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [toolchain.java]
                version = "21"
                distribution = "graalvm-community"
                features = ["native-image"]
                policy = "require-managed"
                """);
        Files.writeString(project.resolve("zolt.lock"), "version = 1\n\n");
        return project;
    }

    private Path fakeWindowsGraalArchive() throws IOException {
        Path archive = tempDir.resolve("windows-graal.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            tool(output, "jdk/bin/java.exe", "java");
            tool(output, "jdk/bin/javac.exe", "javac");
            tool(output, "jdk/bin/jar.exe", "jar");
            tool(output, "jdk/lib/svm/bin/native-image.exe", "native-image");
        }
        return archive;
    }

    private static void tool(ZipOutputStream output, String name, String body) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(body.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private record LocalBundledCatalog(Path archive) implements JavaToolchainCatalog {
        @Override
        public Optional<LockedJavaToolchain> lock(JavaToolchainRequest request, HostPlatform platform) {
            return new BundledJavaToolchainCatalog().lock(request, platform);
        }

        @Override
        public List<LockedJavaToolchain> locks(JavaToolchainRequest request, HostPlatform platform) {
            return new BundledJavaToolchainCatalog().locks(request, platform);
        }

        @Override
        public Optional<JavaToolchainArtifact> artifact(LockedJavaToolchain locked) {
            return Optional.of(new JavaToolchainArtifact(
                    archive.toUri(),
                    JavaToolchainArchiveFormat.ZIP,
                    Optional.empty(),
                    true));
        }
    }
}
