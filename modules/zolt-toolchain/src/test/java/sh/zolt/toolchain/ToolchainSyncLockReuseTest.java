package sh.zolt.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.net.NetworkTransport;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.catalog.JavaToolchainArchiveFormat;
import sh.zolt.toolchain.catalog.JavaToolchainArtifact;
import sh.zolt.toolchain.catalog.JavaToolchainCatalog;
import sh.zolt.toolchain.install.JavaToolchainDownloader;
import sh.zolt.toolchain.install.JavaToolchainInstaller;
import sh.zolt.toolchain.install.ToolchainDownloadMirror;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolchainSyncLockReuseTest {
    @TempDir
    private Path tempDir;

    @Test
    void syncReusesMatchingLockUntilRefreshIsExplicit() throws IOException {
        Path project = writeProject("lock-first-sync");
        Path archive = fakeJdkArchive(tempDir.resolve("refresh-jdk.zip"));
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        RefreshingCatalog catalog = new RefreshingCatalog(archive);
        ToolchainSyncService service = service(catalog, archive);
        JavaToolchainRequest request = temurin();

        service.sync(request, project.resolve("zolt.lock"), HostPlatform.parse("linux-x64"), store);
        assertEquals(1, catalog.lockCalls);
        assertTrue(Files.readString(project.resolve("zolt.lock")).contains("resolved.version = \"21.0.1+1\""));

        service.sync(request, project.resolve("zolt.lock"), HostPlatform.parse("linux-x64"), store);
        assertEquals(1, catalog.lockCalls);

        service.sync(request, project.resolve("zolt.lock"), HostPlatform.parse("linux-x64"), store, true);
        assertEquals(2, catalog.lockCalls);
        assertTrue(Files.readString(project.resolve("zolt.lock")).contains("resolved.version = \"21.0.2+1\""));
    }

    @Test
    void refreshReplacesChecksumlessLegacyLockThatNormalSyncRejects() throws IOException {
        Path project = writeProject("legacy-refresh");
        Path archive = fakeJdkArchive(tempDir.resolve("legacy-refresh.zip"));
        RefreshingCatalog catalog = new RefreshingCatalog(archive);
        ToolchainSyncService service = service(catalog, archive);
        Path lockfile = project.resolve("zolt.lock");
        Files.writeString(lockfile, legacyLock());

        ActionableException rejected = assertThrows(
                ActionableException.class,
                () -> service.sync(
                        temurin(),
                        lockfile,
                        HostPlatform.parse("linux-x64"),
                        new ToolchainStore(tempDir.resolve("legacy-rejected"))));
        assertTrue(rejected.getMessage().contains("toolchain sync --refresh"));

        ToolchainSyncResult refreshed = service.sync(
                temurin(),
                lockfile,
                HostPlatform.parse("linux-x64"),
                new ToolchainStore(tempDir.resolve("legacy-refreshed")),
                true);

        assertTrue(refreshed.installed());
        assertTrue(Files.readString(lockfile).contains("artifact.sha256 = \"" + sha256(archive) + "\""));
    }

    @Test
    void installerRejectsCatalogArtifactThatDiffersFromLock() throws IOException {
        Path project = writeProject("catalog-swap");
        Path archive = fakeJdkArchive(tempDir.resolve("catalog-swap.zip"));
        LockedJavaToolchain locked = new LockedJavaToolchain(
                "java-temurin-21",
                temurin(),
                HostPlatform.parse("linux-x64"),
                "21.0.11+10",
                JavaDistribution.TEMURIN,
                "test",
                artifactUri(archive),
                sha256(archive),
                JavaToolchainLayout.standard(false));
        JavaToolchainArtifact swapped = new JavaToolchainArtifact(
                URI.create("https://github.com/swapped.zip"),
                JavaToolchainArchiveFormat.ZIP,
                Optional.of(locked.artifactSha256()),
                true);

        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> service(new FixedCatalog(locked, swapped), archive).sync(
                        project,
                        null,
                        HostPlatform.parse("linux-x64"),
                        new ToolchainStore(tempDir.resolve("catalog-swap-store"))));

        assertTrue(exception.getMessage().contains("does not match zolt.lock"));
        assertTrue(exception.getMessage().contains("toolchain sync --refresh"));
    }

    private ToolchainSyncService service(JavaToolchainCatalog catalog, Path archive) {
        ToolchainDownloadMirror mirror = ToolchainDownloadMirror.of(
                archive.toAbsolutePath().getParent().toUri().toString());
        return new ToolchainSyncService(
                new ToolchainConfigReader(),
                catalog,
                new ToolchainLockfileService(),
                new JavaToolchainInstaller(new JavaToolchainDownloader(NetworkTransport.direct(), mirror)));
    }

    private Path writeProject(String name) throws IOException {
        Path project = Files.createDirectory(tempDir.resolve(name));
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [toolchain.java]
                version = "21"
                distribution = "temurin"
                features = []
                policy = "require-managed"
                """.formatted(name));
        Files.writeString(project.resolve("zolt.lock"), "version = 1\n\n");
        return project;
    }

    private static Path fakeJdkArchive(Path archive) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            tool(output, "jdk/bin/java", "java");
            tool(output, "jdk/bin/javac", "javac");
            tool(output, "jdk/bin/jar", "jar");
        }
        return archive;
    }

    private static void tool(ZipOutputStream output, String name, String body) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static JavaToolchainRequest temurin() {
        return new JavaToolchainRequest(
                "21",
                JavaDistribution.TEMURIN,
                Set.of(),
                ToolchainPolicy.REQUIRE_MANAGED);
    }

    private static String artifactUri(Path archive) {
        return "https://github.com/" + archive.getFileName();
    }

    private static String sha256(Path archive) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String legacyLock() {
        return """
                version = 1

                [[toolchain.java]]
                id = "java-temurin-21"
                request.version = "21"
                request.distribution = "temurin"
                request.features = []
                request.policy = "require-managed"
                platform.os = "linux"
                platform.arch = "x64"
                resolved.version = "21"
                resolved.distribution = "temurin"
                artifact.catalog = "legacy"
                artifact.uri = "https://example.test/legacy.zip"
                layout.javaHome = "."
                layout.executables.java = "bin/java"
                layout.executables.javac = "bin/javac"
                layout.executables.jar = "bin/jar"
                """;
    }

    private record FixedCatalog(
            LockedJavaToolchain locked,
            JavaToolchainArtifact artifact) implements JavaToolchainCatalog {
        @Override
        public Optional<LockedJavaToolchain> lock(JavaToolchainRequest request, HostPlatform platform) {
            return Optional.of(locked);
        }

        @Override
        public Optional<JavaToolchainArtifact> artifact(LockedJavaToolchain ignored) {
            return Optional.of(artifact);
        }
    }

    private static final class RefreshingCatalog implements JavaToolchainCatalog {
        private final Path archive;
        private int lockCalls;

        private RefreshingCatalog(Path archive) {
            this.archive = archive;
        }

        @Override
        public Optional<LockedJavaToolchain> lock(JavaToolchainRequest request, HostPlatform platform) {
            lockCalls++;
            return Optional.of(new LockedJavaToolchain(
                    "java-temurin-21",
                    request,
                    platform,
                    "21.0." + lockCalls + "+1",
                    JavaDistribution.TEMURIN,
                    "test:refresh",
                    artifactUri(archive),
                    sha256(archive),
                    JavaToolchainLayout.standard(false)));
        }

        @Override
        public Optional<JavaToolchainArtifact> artifact(LockedJavaToolchain locked) {
            return Optional.of(new JavaToolchainArtifact(
                    URI.create(locked.artifactUri()),
                    JavaToolchainArchiveFormat.ZIP,
                    Optional.of(locked.artifactSha256()),
                    true));
        }
    }
}
