package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.classpath.LockfileClasspathPackageConverter;
import sh.zolt.build.lockfile.VerifiedArtifactIndex;
import sh.zolt.lockfile.toml.LockfileReadException;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.fingerprint.ProjectResolutionFingerprint;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.resolve.WorkspaceResolveService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CommandLockfilesTest {
    private static final byte[] JAR_BYTES = "jar bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] POM_BYTES = "pom bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECONDARY_BYTES = "secondary bytes".getBytes(StandardCharsets.UTF_8);
    private static final String JAR_PATH = cachePath("demo.jar", JAR_BYTES);
    private static final String POM_PATH = cachePath("demo.pom", POM_BYTES);
    private static final String SECONDARY_PATH = cachePath("demo.properties", SECONDARY_BYTES);

    @TempDir
    Path tempDir;

    @Test
    void acceptsMatchingProjectResolutionFingerprintWithoutResolvingGraph() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path lockfile = tempDir.resolve("zolt.lock");
        Files.writeString(lockfile, """
                version = 7
                projectResolutionFingerprint = "%s"

                [[package]]
                id = "com.example:demo"
                """.formatted(ProjectResolutionFingerprint.fingerprint(config)));

        assertTrue(CommandLockfiles.matchesProjectResolutionFingerprint(lockfile, config));
        assertFalse(CommandLockfiles.matchesProjectResolutionFingerprint(lockfile, config("2.0.0")));
    }

    @Test
    void requiresFullVerificationWhenFingerprintIsMissing() throws Exception {
        Path lockfile = tempDir.resolve("zolt.lock");
        Files.writeString(lockfile, "version = 7\n");

        assertFalse(CommandLockfiles.matchesProjectResolutionFingerprint(lockfile, config("1.0.0")));
    }

    @Test
    void matchingLockWithVerifiedArtifactsSkipsLockedResolve() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = writeLock(config, true);
        AtomicInteger resolves = new AtomicInteger();

        lockfiles(resolves, new AtomicReference<>())
                .requireFreshLockfile(project, config, cacheRoot(), false);

        assertEquals(0, resolves.get());
    }

    @Test
    void returnsTheFreshnessIndexForDownstreamClasspathProjection() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = writeLock(config, true);
        CommandLockfiles lockfiles = lockfiles(new AtomicInteger(), new AtomicReference<>());

        VerifiedArtifactIndex index = lockfiles.requireFreshLockfile(
                project, config, cacheRoot(), false);
        LockfileClasspathPackageConverter.classpathPackages(
                new ZoltLockfileReader().read(project.resolve("zolt.lock")),
                cacheRoot(),
                index);

        assertEquals(3, index.metrics().hashes());
        assertEquals(3, index.metrics().cacheHits());
    }

    @Test
    void matchingLockWithEmptyCacheRequiresLockedMaterialization() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = writeLock(config, false);
        AtomicInteger resolves = new AtomicInteger();

        lockfiles(resolves, new AtomicReference<>())
                .requireFreshLockfile(project, config, cacheRoot(), false);

        assertEquals(1, resolves.get());
    }

    @Test
    void currentVersionPlaceholderRequiresLockedVerification() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = tempDir.resolve("placeholder-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.lock"), "version = 7\n");
        AtomicInteger resolves = new AtomicInteger();

        lockfiles(resolves, new AtomicReference<>())
                .requireFreshLockfile(project, config, cacheRoot(), false);

        assertEquals(1, resolves.get());
    }

    @Test
    void legacyMetadataOnlyLockRequiresLockedVerification() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = tempDir.resolve("legacy-metadata-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.lock"), "version = 5\n");
        AtomicInteger resolves = new AtomicInteger();

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> lockfiles(resolves, new AtomicReference<>())
                        .requireFreshLockfile(project, config, cacheRoot(), false));

        assertTrue(exception.getMessage().contains("version 5 is older than this Zolt supports (current 7)"));
        assertEquals(0, resolves.get());
    }

    @Test
    void matchingLockWithOnlyJarMissingRequiresLockedMaterialization() throws Exception {
        assertMissingArtifactRequiresMaterialization(JAR_PATH);
    }

    @Test
    void matchingLockWithOnlyPomMissingRequiresLockedMaterialization() throws Exception {
        assertMissingArtifactRequiresMaterialization(POM_PATH);
    }

    @Test
    void matchingLockWithSecondaryArtifactMissingRequiresLockedMaterialization() throws Exception {
        assertMissingArtifactRequiresMaterialization(SECONDARY_PATH);
    }

    @Test
    void matchingLockWithCorruptBytesRequiresLockedMaterialization() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = writeLock(config, true);
        Files.writeString(cacheRoot().resolve(JAR_PATH), "corrupt");
        AtomicInteger resolves = new AtomicInteger();

        lockfiles(resolves, new AtomicReference<>())
                .requireFreshLockfile(project, config, cacheRoot(), false);

        assertEquals(1, resolves.get());
    }

    @Test
    void incompleteOfflineCacheUsesLockedOfflineResolve() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = writeLock(config, false);
        AtomicInteger resolves = new AtomicInteger();
        AtomicReference<ResolveOptions> options = new AtomicReference<>();

        lockfiles(resolves, options)
                .requireFreshLockfile(project, config, cacheRoot(), true, "zolt build");

        assertEquals(1, resolves.get());
        assertTrue(options.get().offline());
        assertEquals("zolt build", options.get().retryCommand());
    }

    @Test
    void versionFiveLockRequiresMigrationBeforeLockedMaterialization() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = writeLock(config, false);
        Path lockfile = project.resolve("zolt.lock");
        Files.writeString(lockfile, Files.readString(lockfile)
                .replaceFirst("version = 7", "version = 5")
                .replace(JAR_PATH, "com/example/demo/1.0.0/demo-1.0.0.jar")
                .replace(POM_PATH, "com/example/demo/1.0.0/demo-1.0.0.pom")
                .replace(SECONDARY_PATH, "com/example/demo/1.0.0/demo-1.0.0.properties"));
        AtomicInteger resolves = new AtomicInteger();

        LockfileReadException exception = assertThrows(
                LockfileReadException.class,
                () -> lockfiles(resolves, new AtomicReference<>())
                        .requireFreshLockfile(project, config, cacheRoot(), false));

        assertTrue(exception.getMessage().contains("version 5 is older than this Zolt supports (current 7)"));
        assertTrue(exception.getMessage().contains("zolt resolve"));
        assertEquals(0, resolves.get());
    }

    private void assertMissingArtifactRequiresMaterialization(String relativePath) throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = writeLock(config, true);
        Files.delete(cacheRoot().resolve(relativePath));
        AtomicInteger resolves = new AtomicInteger();

        lockfiles(resolves, new AtomicReference<>())
                .requireFreshLockfile(project, config, cacheRoot(), false);

        assertEquals(1, resolves.get(), relativePath);
    }

    private CommandLockfiles lockfiles(
            AtomicInteger resolves,
            AtomicReference<ResolveOptions> options) {
        return new CommandLockfiles(
                (workingDirectory, config, cacheRoot, locked, resolveOptions) -> {
                    assertTrue(locked);
                    resolves.incrementAndGet();
                    options.set(resolveOptions);
                },
                new ManifestWorkspaceLoader(),
                new WorkspaceResolveService(),
                new ZoltLockfileReader(),
                new VerifiedArtifactIndex());
    }

    private Path writeLock(ProjectConfig config, boolean materialize) throws Exception {
        Path project = tempDir.resolve("locked-project");
        Files.createDirectories(project);
        if (materialize) {
            writeCacheArtifact(JAR_PATH, JAR_BYTES);
            writeCacheArtifact(POM_PATH, POM_BYTES);
            writeCacheArtifact(SECONDARY_PATH, SECONDARY_BYTES);
        }
        Files.writeString(project.resolve("zolt.lock"), """
                version = 7
                projectResolutionFingerprint = "%s"

                [[dependencyRoot]]
                member = "."
                id = "com.example:demo"
                version = "1.0.0"
                variant = "properties"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:demo"
                version = "1.0.0"
                source = "test"
                scope = "compile"
                direct = true
                jar = "%s"
                pom = "%s"
                jarSha256 = "%s"
                pomSha256 = "%s"
                artifact = "%s"
                artifactType = "properties"
                artifactSha256 = "%s"
                dependencies = []
                """.formatted(
                ProjectResolutionFingerprint.fingerprint(config),
                JAR_PATH,
                POM_PATH,
                sha256(JAR_BYTES),
                sha256(POM_BYTES),
                SECONDARY_PATH,
                sha256(SECONDARY_BYTES)));
        return project;
    }

    private void writeCacheArtifact(String relativePath, byte[] bytes) throws Exception {
        Path path = cacheRoot().resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private Path cacheRoot() {
        return tempDir.resolve("artifact-cache");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String cachePath(String filename, byte[] bytes) {
        return "blobs/v2/sha256/" + sha256(bytes) + "/" + filename;
    }

    private ProjectConfig config(String dependencyVersion) throws Exception {
        Path project = tempDir.resolve("project-" + dependencyVersion);
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "com.example:demo" = "%s"
                """.formatted(dependencyVersion));
        return new ZoltTomlParser().parse(project.resolve("zolt.toml"));
    }
}
