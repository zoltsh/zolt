package sh.zolt.cli.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.lockfile.ArtifactIntegrityVerifier;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveOptions;
import sh.zolt.resolve.fingerprint.ProjectResolutionFingerprint;
import sh.zolt.toml.ZoltTomlParser;
import sh.zolt.workspace.discovery.WorkspaceDiscoveryService;
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
    @TempDir
    Path tempDir;

    @Test
    void acceptsMatchingProjectResolutionFingerprintWithoutResolvingGraph() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path lockfile = tempDir.resolve("zolt.lock");
        Files.writeString(lockfile, """
                version = 1
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
        Files.writeString(lockfile, "version = 1\n");

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
    void matchingLockWithEmptyCacheRequiresLockedMaterialization() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = writeLock(config, false);
        AtomicInteger resolves = new AtomicInteger();

        lockfiles(resolves, new AtomicReference<>())
                .requireFreshLockfile(project, config, cacheRoot(), false);

        assertEquals(1, resolves.get());
    }

    @Test
    void matchingLockWithOnlyJarMissingRequiresLockedMaterialization() throws Exception {
        assertMissingArtifactRequiresMaterialization("cache/demo.jar");
    }

    @Test
    void matchingLockWithOnlyPomMissingRequiresLockedMaterialization() throws Exception {
        assertMissingArtifactRequiresMaterialization("cache/demo.pom");
    }

    @Test
    void matchingLockWithSecondaryArtifactMissingRequiresLockedMaterialization() throws Exception {
        assertMissingArtifactRequiresMaterialization("cache/demo.properties");
    }

    @Test
    void matchingLockWithCorruptBytesRequiresLockedMaterialization() throws Exception {
        ProjectConfig config = config("1.0.0");
        Path project = writeLock(config, true);
        Files.writeString(cacheRoot().resolve("cache/demo.jar"), "corrupt");
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
                new WorkspaceDiscoveryService(),
                new WorkspaceResolveService(),
                new ZoltLockfileReader(),
                new ArtifactIntegrityVerifier());
    }

    private Path writeLock(ProjectConfig config, boolean materialize) throws Exception {
        Path project = tempDir.resolve("locked-project");
        Files.createDirectories(project);
        byte[] jar = "jar bytes".getBytes(StandardCharsets.UTF_8);
        byte[] pom = "pom bytes".getBytes(StandardCharsets.UTF_8);
        byte[] artifact = "secondary bytes".getBytes(StandardCharsets.UTF_8);
        if (materialize) {
            writeCacheArtifact("cache/demo.jar", jar);
            writeCacheArtifact("cache/demo.pom", pom);
            writeCacheArtifact("cache/demo.properties", artifact);
        }
        Files.writeString(project.resolve("zolt.lock"), """
                version = 5
                projectResolutionFingerprint = "%s"

                [[package]]
                id = "com.example:demo"
                version = "1.0.0"
                source = "test"
                scope = "compile"
                direct = true
                jar = "cache/demo.jar"
                pom = "cache/demo.pom"
                jarSha256 = "%s"
                pomSha256 = "%s"
                artifact = "cache/demo.properties"
                artifactType = "properties"
                artifactSha256 = "%s"
                dependencies = []
                """.formatted(
                ProjectResolutionFingerprint.fingerprint(config),
                sha256(jar),
                sha256(pom),
                sha256(artifact)));
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

    private ProjectConfig config(String dependencyVersion) throws Exception {
        Path project = tempDir.resolve("project-" + dependencyVersion);
        Files.createDirectories(project);
        Files.writeString(project.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = "21"

                [dependencies]
                "com.example:demo" = "%s"
                """.formatted(dependencyVersion));
        return new ZoltTomlParser().parse(project.resolve("zolt.toml"));
    }
}
