package sh.zolt.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.cache.BuildCacheService;
import sh.zolt.build.cache.BuildCacheSettings;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design §4.5: a workspace has exactly one authoritative {@code zolt.lock}, at its root, and no
 * command creates or consumes a member-local one. That makes a member-local {@code zolt.lock}
 * <em>observationally irrelevant</em> — the strong form of the rule, and the one worth testing,
 * because the weak form ("we resolve from the root lock") was already true while the bug was live.
 *
 * <p>The lock's content hash is a direct build-fingerprint input. A service handed only the member
 * directory can only derive {@code <member>/zolt.lock}, so it hashes a file that is normally MISSING;
 * the moment a stray one appears the fingerprint moves, the skip gate misses, and the build-cache key
 * changes — all while the member's dependency bytes are exactly what they were. Nothing about the
 * build is different; only a file nobody is allowed to read.
 *
 * <p>These tests plant a valid, plausible, deliberately poisoned member lock and assert the build does
 * not notice. {@link #rootLockChangeDoesAffectMemberFingerprint()} is the control: the AUTHORITATIVE
 * lock must still be an input, or "notices nothing" would be satisfied by ignoring the lock entirely.
 */
final class StrayMemberLockIrrelevanceTest {
    /** A member lock that parses and names a dependency the member does not have. */
    private static final String POISONED_MEMBER_LOCK = """
            version = 7

            [[package]]
            id = "com.example:phantom"
            version = "9.9.9"
            source = "maven-central"
            scope = "main"
            direct = true
            jar = "com/example/phantom/9.9.9/phantom-9.9.9.jar"
            dependencies = []
            """;

    private final BuildFingerprintService fingerprints = new BuildFingerprintService();

    @TempDir
    private Path tempDir;

    @TempDir
    private Path cacheHome;

    @Test
    void strayMemberLockDoesNotChangeMainFingerprint() throws IOException {
        Workspace workspace = workspace();
        BuildService service = new BuildService();

        BuildResult first = service.build(
                workspace.context("apps/api"), workspace.config("api"), classpaths(), List.of());
        assertFalse(first.mainCompilationSkipped(), "the first build compiles");
        String fingerprintBefore = storedMainFingerprint(workspace.output("apps/api"));

        workspace.plantMemberLock("apps/api", POISONED_MEMBER_LOCK);
        BuildResult second = service.build(
                workspace.context("apps/api"), workspace.config("api"), classpaths(), List.of());

        assertTrue(
                second.mainCompilationSkipped(),
                () -> "a member-local zolt.lock is not an input, so the skip gate stays current; got "
                        + second.mainIncrementalFallbackReason());
        assertEquals(
                fingerprintBefore,
                storedMainFingerprint(workspace.output("apps/api")),
                "the main-compile fingerprint is unchanged by a file no command may read");
    }

    /**
     * The build-cache key is derived from the same inputs fingerprint, so a stray member lock that
     * moved the fingerprint would also split the cache: the same source, on the same JDK, would key
     * differently depending on whether a file nobody reads happens to exist.
     */
    @Test
    void strayMemberLockDoesNotChangeBuildCacheKey() throws IOException {
        Workspace workspace = workspace();
        BuildCacheSettings settings = new BuildCacheSettings(true, cacheHome.resolve("build-cache"), 0L);
        BuildService service = new BuildService()
                .withBuildCache(BuildCacheService.create(settings, "test-version"));

        BuildResult stored = service.build(
                workspace.context("apps/api"), workspace.config("api"), classpaths(), List.of());
        assertEquals("stored", stored.mainBuildCacheOutcome(), "the first build populates the cache");

        workspace.wipeOutput("apps/api");
        workspace.plantMemberLock("apps/api", POISONED_MEMBER_LOCK);
        BuildResult afterStrayLock = service.build(
                workspace.context("apps/api"), workspace.config("api"), classpaths(), List.of());

        assertTrue(
                afterStrayLock.mainCompilationRestored(),
                "a stray member lock must not change the cache key, so the wiped output still restores");
        assertEquals("restored", afterStrayLock.mainBuildCacheOutcome());
    }

    /**
     * The control. Without it, "the fingerprint did not move" would also pass for a build that stopped
     * hashing the lock at all — which would be a worse bug than the one being fixed.
     */
    @Test
    void rootLockChangeDoesAffectMemberFingerprint() throws IOException {
        Workspace workspace = workspace();
        BuildService service = new BuildService();

        BuildResult first = service.build(
                workspace.context("apps/api"), workspace.config("api"), classpaths(), List.of());
        assertFalse(first.mainCompilationSkipped());
        String fingerprintBefore = storedMainFingerprint(workspace.output("apps/api"));

        workspace.rewriteRootLock(POISONED_MEMBER_LOCK);
        BuildResult second = service.build(
                workspace.context("apps/api"), workspace.config("api"), classpaths(), List.of());

        assertFalse(
                second.mainCompilationSkipped(),
                "the workspace root's lock is the authoritative dependency identity and must invalidate");
        assertEquals("fingerprint-mismatch:lockfile", second.mainIncrementalFallbackReason());
        assertNotEquals(
                fingerprintBefore,
                storedMainFingerprint(workspace.output("apps/api")),
                "a real dependency-identity change moves the fingerprint");
    }

    private String storedMainFingerprint(Path outputDirectory) {
        return fingerprints.storedMainInputsFingerprintSha256(outputDirectory);
    }

    private static ClasspathSet classpaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty, empty);
    }

    private Workspace workspace() throws IOException {
        Path root = tempDir.resolve("platform");
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*", "modules/*"]
                """);
        Files.writeString(root.resolve("zolt.lock"), "version = 7\n");
        Workspace workspace = new Workspace(root);
        workspace.member("apps/api", "api", "com.example.api");
        workspace.member("modules/core", "core", "com.example.core");
        return workspace;
    }

    /** A two-member workspace: one authoritative lock at the root, two member directories under it. */
    private static final class Workspace {
        private final Path root;

        private Workspace(Path root) {
            this.root = root;
        }

        void member(String memberPath, String name, String packageName) throws IOException {
            Path directory = root.resolve(memberPath);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("zolt.toml"), """
                    [project]
                    name = "%s"
                    """.formatted(name));
            Path source = directory.resolve(
                    "src/main/java/" + packageName.replace('.', '/') + "/Component.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, """
                    package %s;

                    public final class Component {
                        public static String name() {
                            return "%s";
                        }
                    }
                    """.formatted(packageName, name));
        }

        /** Exactly what {@code Workspace.memberContext} hands the build service for this member. */
        ProjectBuildContext context(String memberPath) {
            return ProjectBuildContext.member(
                    root.resolve(memberPath), root.resolve("zolt.lock"), memberPath);
        }

        ProjectConfig config(String name) {
            return ProjectConfigs.withDirectDependencies(
                    new ProjectMetadata(
                            name,
                            "0.1.0",
                            "com.example",
                            currentJavaMajorVersion(),
                            Optional.empty()),
                    ProjectConfig.defaultRepositories(),
                    Map.of(),
                    Map.of(),
                    BuildSettings.defaults());
        }

        Path output(String memberPath) {
            return root.resolve(memberPath).resolve("target/classes");
        }

        void plantMemberLock(String memberPath, String content) throws IOException {
            Files.writeString(root.resolve(memberPath).resolve("zolt.lock"), content);
        }

        void rewriteRootLock(String content) throws IOException {
            Files.writeString(root.resolve("zolt.lock"), content);
        }

        void wipeOutput(String memberPath) throws IOException {
            Path target = root.resolve(memberPath).resolve("target");
            if (!Files.exists(target)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(target)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                });
            }
        }
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
