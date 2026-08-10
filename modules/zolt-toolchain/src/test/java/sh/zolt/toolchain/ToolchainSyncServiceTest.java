package sh.zolt.toolchain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import sh.zolt.project.toolchain.JavaDistribution;
import sh.zolt.project.toolchain.JavaFeature;
import sh.zolt.project.toolchain.JavaToolchainRequest;
import sh.zolt.project.toolchain.ToolchainPolicy;
import sh.zolt.toolchain.catalog.JavaToolchainArchiveFormat;
import sh.zolt.toolchain.catalog.JavaToolchainArtifact;
import sh.zolt.toolchain.catalog.JavaToolchainCatalog;
import sh.zolt.toolchain.install.JavaToolchainInstaller;
import sh.zolt.toolchain.lock.JavaToolchainLayout;
import sh.zolt.toolchain.lock.LockedJavaToolchain;
import sh.zolt.toolchain.lock.ToolchainLockfileService;
import sh.zolt.toolchain.platform.HostPlatform;
import sh.zolt.toolchain.store.ToolchainStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ToolchainSyncServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void syncDownloadsAndInstallsLockedJavaToolchain() throws IOException {
        Path project = writeProject("download-sync");
        Path archive = fakeJdkArchive(tempDir.resolve("jdk.zip"), false);
        LockedJavaToolchain locked = locked(JavaDistribution.TEMURIN, Set.of());
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        ToolchainSyncService service = service(locked, artifact(archive, false));

        ToolchainSyncResult result = service.sync(
                project,
                null,
                HostPlatform.parse("linux-x64"),
                store);

        assertTrue(result.installed());
        assertTrue(result.downloaded());
        assertTrue(store.installed(locked));
        assertTrue(Files.isExecutable(store.java(locked)));
        assertTrue(Files.isExecutable(store.javac(locked)));
        assertTrue(Files.isExecutable(store.jar(locked)));
        assertTrue(Files.readString(store.java(locked)).contains("java"));
        assertTrue(Files.readString(project.resolve("zolt.lock")).contains("[[toolchain.java]]"));
    }

    @Test
    void syncInstallsNativeImageWhenRequested() throws IOException {
        Path project = writeProject("native-sync");
        Path archive = fakeJdkArchive(tempDir.resolve("graal.zip"), true);
        LockedJavaToolchain locked = locked(JavaDistribution.GRAALVM_COMMUNITY, Set.of(JavaFeature.NATIVE_IMAGE));
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));

        ToolchainSyncResult result = service(locked, artifact(archive, true)).sync(
                project,
                null,
                HostPlatform.parse("linux-x64"),
                store);

        assertTrue(result.installed());
        assertTrue(store.nativeImage(locked).map(Files::isExecutable).orElse(false));
    }

    @Test
    void syncInstallsMacGraalVmContentsHomeLayout() throws IOException {
        Path project = writeProject("mac-graal-sync");
        Path archive = fakeMacGraalArchive(tempDir.resolve("mac-graal.zip"));
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                Set.of(JavaFeature.NATIVE_IMAGE),
                ToolchainPolicy.REQUIRE_MANAGED);
        LockedJavaToolchain locked = new LockedJavaToolchain(
                "java-graalvm-community-21-native-image",
                request,
                HostPlatform.parse("macos-aarch64"),
                "21",
                JavaDistribution.GRAALVM_COMMUNITY,
                "test",
                new JavaToolchainLayout(
                        "Contents/Home",
                        "bin/java",
                        "bin/javac",
                        "bin/jar",
                        "lib/svm/bin/native-image"));
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));

        ToolchainSyncResult result = service(locked, artifact(archive, true)).sync(
                project,
                null,
                HostPlatform.parse("macos-aarch64"),
                store);

        assertTrue(result.installed());
        assertTrue(Files.isExecutable(store.java(locked)));
        assertTrue(store.nativeImage(locked).map(Files::isExecutable).orElse(false));
    }

    @Test
    void syncSkipsDownloadWhenToolchainIsAlreadyInstalled() throws IOException {
        Path project = writeProject("already-installed");
        LockedJavaToolchain locked = locked(JavaDistribution.TEMURIN, Set.of());
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        install(store, locked);

        ToolchainSyncResult result = service(
                locked,
                artifact(tempDir.resolve("missing.zip"), false)).sync(
                        project,
                        null,
                        HostPlatform.parse("linux-x64"),
                        store);

        assertTrue(result.installed());
        assertFalse(result.downloaded());
    }

    @Test
    void syncRejectsDownloadedToolchainWhenChecksumDoesNotMatch() throws IOException {
        Path project = writeProject("checksum-mismatch");
        Path archive = fakeJdkArchive(tempDir.resolve("bad-checksum.zip"), false);
        LockedJavaToolchain locked = locked(JavaDistribution.TEMURIN, Set.of());
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));

        ActionableException exception = assertThrows(ActionableException.class, () -> service(
                locked,
                artifact(archive, false, "definitely-wrong")).sync(
                        project,
                        null,
                        HostPlatform.parse("linux-x64"),
                        store));

        assertTrue(exception.getMessage().contains("checksum did not match"));
        assertFalse(store.installed(locked));
    }

    @Test
    void syncFailsClearlyWhenCatalogHasNoDownloadableArtifact() throws IOException {
        Path project = writeProject("missing-artifact");
        LockedJavaToolchain locked = locked(JavaDistribution.TEMURIN, Set.of());
        ToolchainSyncService service = new ToolchainSyncService(
                new ToolchainConfigReader(),
                new FakeCatalog(locked, Optional.empty()),
                new ToolchainLockfileService(),
                new JavaToolchainInstaller());

        ActionableException exception = assertThrows(ActionableException.class, () -> service.sync(
                project,
                null,
                HostPlatform.parse("linux-x64"),
                new ToolchainStore(tempDir.resolve("toolchains"))));

        assertTrue(exception.getMessage().contains("No downloadable Java toolchain artifact"));
    }

    @Test
    void syncInstallsMainAndTestRuntimeToolchainsAsAdditiveLockEntries() throws IOException {
        Path project = writeProjectWithTestToolchain("additive-sync", "17");
        Path archive = fakeJdkArchive(tempDir.resolve("jdk.zip"), false);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        ToolchainSyncService service = new ToolchainSyncService(
                new ToolchainConfigReader(),
                new VersionAwareCatalog(archive),
                new ToolchainLockfileService(),
                new JavaToolchainInstaller());

        service.sync(project, null, HostPlatform.parse("linux-x64"), store);

        String lock = Files.readString(project.resolve("zolt.lock"));
        assertEquals(2, countOccurrences(lock, "[[toolchain.java]]"));
        assertTrue(lock.contains("request.version = \"21\""));
        assertTrue(lock.contains("request.version = \"17\""));
        assertTrue(store.installed(new VersionAwareCatalog(archive)
                .lock(temurin("21"), HostPlatform.parse("linux-x64")).orElseThrow()));
        assertTrue(store.installed(new VersionAwareCatalog(archive)
                .lock(temurin("17"), HostPlatform.parse("linux-x64")).orElseThrow()));
    }

    @Test
    void syncDedupsEqualVersionTestRuntimeToolchain() throws IOException {
        Path project = writeProjectWithTestToolchain("dedup-sync", "21");
        Path archive = fakeJdkArchive(tempDir.resolve("jdk.zip"), false);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        ToolchainSyncService service = new ToolchainSyncService(
                new ToolchainConfigReader(),
                new VersionAwareCatalog(archive),
                new ToolchainLockfileService(),
                new JavaToolchainInstaller());

        service.sync(project, null, HostPlatform.parse("linux-x64"), store);

        String lock = Files.readString(project.resolve("zolt.lock"));
        assertEquals(1, countOccurrences(lock, "[[toolchain.java]]"));
    }

    @Test
    void syncReusesMatchingLockUntilRefreshIsExplicit() throws IOException {
        Path project = writeProject("lock-first-sync");
        Path archive = fakeJdkArchive(tempDir.resolve("refresh-jdk.zip"), false);
        ToolchainStore store = new ToolchainStore(tempDir.resolve("toolchains"));
        RefreshingCatalog catalog = new RefreshingCatalog(archive);
        ToolchainSyncService service = new ToolchainSyncService(
                new ToolchainConfigReader(),
                catalog,
                new ToolchainLockfileService(),
                new JavaToolchainInstaller());
        JavaToolchainRequest request = temurin("21");

        service.sync(request, project.resolve("zolt.lock"), HostPlatform.parse("linux-x64"), store);
        assertEquals(1, catalog.lockCalls);
        assertTrue(Files.readString(project.resolve("zolt.lock")).contains("resolved.version = \"21.0.1+1\""));

        service.sync(request, project.resolve("zolt.lock"), HostPlatform.parse("linux-x64"), store);
        assertEquals(1, catalog.lockCalls);

        service.sync(request, project.resolve("zolt.lock"), HostPlatform.parse("linux-x64"), store, true);
        assertEquals(2, catalog.lockCalls);
        assertTrue(Files.readString(project.resolve("zolt.lock")).contains("resolved.version = \"21.0.2+1\""));
    }

    private static JavaToolchainRequest temurin(String version) {
        return new JavaToolchainRequest(version, JavaDistribution.TEMURIN, Set.of(), ToolchainPolicy.REQUIRE_MANAGED);
    }

    private static int countOccurrences(String content, String token) {
        int count = 0;
        int index = content.indexOf(token);
        while (index >= 0) {
            count++;
            index = content.indexOf(token, index + token.length());
        }
        return count;
    }

    private Path writeProjectWithTestToolchain(String name, String testVersion) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = "17"

                [toolchain.java]
                version = "21"
                distribution = "temurin"
                features = []
                policy = "require-managed"

                [toolchain.java.test]
                version = "%s"
                """.formatted(name, testVersion));
        Files.writeString(project.resolve("zolt.lock"), "version = 1\n\n");
        return project;
    }

    private Path writeProject(String name) throws IOException {
        Path project = tempDir.resolve(name);
        Files.createDirectories(project);
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

    private ToolchainSyncService service(LockedJavaToolchain locked, JavaToolchainArtifact artifact) {
        return new ToolchainSyncService(
                new ToolchainConfigReader(),
                new FakeCatalog(locked, Optional.of(artifact)),
                new ToolchainLockfileService(),
                new JavaToolchainInstaller());
    }

    private static JavaToolchainArtifact artifact(Path archive, boolean nativeImage) {
        return artifact(archive, nativeImage, "");
    }

    private static JavaToolchainArtifact artifact(Path archive, boolean nativeImage, String sha256) {
        return new JavaToolchainArtifact(
                archive.toUri(),
                JavaToolchainArchiveFormat.ZIP,
                sha256.isBlank() ? Optional.empty() : Optional.of(sha256),
                true);
    }

    private static Path fakeJdkArchive(Path archive, boolean nativeImage) throws IOException {
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            tool(output, "jdk/bin/java", "java");
            tool(output, "jdk/bin/javac", "javac");
            tool(output, "jdk/bin/jar", "jar");
            if (nativeImage) {
                tool(output, "jdk/bin/native-image", "native-image");
            }
        }
        return archive;
    }

    private static Path fakeMacGraalArchive(Path archive) throws IOException {
        Files.createDirectories(archive.getParent());
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            tool(output, "jdk/Contents/Home/bin/java", "java");
            tool(output, "jdk/Contents/Home/bin/javac", "javac");
            tool(output, "jdk/Contents/Home/bin/jar", "jar");
            tool(output, "jdk/Contents/Home/lib/svm/bin/native-image", "native-image");
        }
        return archive;
    }

    private static void tool(ZipOutputStream output, String name, String body) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static LockedJavaToolchain locked(JavaDistribution distribution, Set<JavaFeature> features) {
        JavaToolchainRequest request = new JavaToolchainRequest(
                "21",
                distribution,
                features,
                ToolchainPolicy.REQUIRE_MANAGED);
        return new LockedJavaToolchain(
                "java-" + distribution.id() + "-21" + (features.contains(JavaFeature.NATIVE_IMAGE) ? "-native-image" : ""),
                request,
                HostPlatform.parse("linux-x64"),
                "21",
                distribution,
                "test",
                JavaToolchainLayout.standard(features.contains(JavaFeature.NATIVE_IMAGE)));
    }

    private static void install(ToolchainStore store, LockedJavaToolchain locked) throws IOException {
        install(store.java(locked));
        install(store.javac(locked));
        install(store.jar(locked));
    }

    private static void install(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "");
        path.toFile().setExecutable(true);
    }

    private record FakeCatalog(
            LockedJavaToolchain locked,
            Optional<JavaToolchainArtifact> artifact) implements JavaToolchainCatalog {
        @Override
        public Optional<LockedJavaToolchain> lock(JavaToolchainRequest request, HostPlatform platform) {
            return Optional.of(locked);
        }

        @Override
        public Optional<JavaToolchainArtifact> artifact(LockedJavaToolchain locked) {
            return artifact;
        }
    }

    /** Catalog that mints a distinct lock entry per requested version, so additive multi-version syncs can be exercised. */
    private record VersionAwareCatalog(Path archive) implements JavaToolchainCatalog {
        @Override
        public Optional<LockedJavaToolchain> lock(JavaToolchainRequest request, HostPlatform platform) {
            boolean nativeImage = request.features().contains(JavaFeature.NATIVE_IMAGE);
            JavaDistribution distribution = request.distribution().orElseThrow();
            String id = "java-" + distribution.id() + "-" + request.version()
                    + (nativeImage ? "-native-image" : "");
            return Optional.of(new LockedJavaToolchain(
                    id,
                    request,
                    platform,
                    request.version(),
                    distribution,
                    "test:" + id,
                    JavaToolchainLayout.standard(nativeImage)));
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

    private static final class RefreshingCatalog implements JavaToolchainCatalog {
        private final Path archive;
        private int lockCalls;

        private RefreshingCatalog(Path archive) {
            this.archive = archive;
        }

        @Override
        public Optional<LockedJavaToolchain> lock(JavaToolchainRequest request, HostPlatform platform) {
            lockCalls++;
            JavaDistribution distribution = request.distribution().orElseThrow();
            return Optional.of(new LockedJavaToolchain(
                    "java-" + distribution.id() + "-" + request.version(),
                    request,
                    platform,
                    request.version() + ".0." + lockCalls + "+1",
                    distribution,
                    "test:refresh",
                    JavaToolchainLayout.standard(false)));
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
