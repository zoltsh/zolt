package sh.zolt.build.testruntime.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import sh.zolt.build.BuildResult;
import sh.zolt.build.BuildService;
import sh.zolt.build.fingerprint.BuildFingerprintService;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.BuildSettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.ProjectConfigs;
import sh.zolt.project.ProjectMetadata;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The test-compile half of the member-lock invariant (design §4.5).
 *
 * <p>Test freshness and the test build-cache key hash the lockfile exactly as the main compile does,
 * so the same defect lands here: a service handed only the member directory fingerprints
 * {@code <member>/zolt.lock}, and a stray one recompiles tests whose inputs never moved.
 *
 * <p>{@link #unreadableMemberLockCannotBreakWorkspaceBuild()} pushes on the sharper edge of the same
 * rule. "Irrelevant" is not "hashed to the same value" — it is "never touched". A member-local path
 * that cannot be read at all must be a non-event; if the build fingerprints it, the build fails on a
 * file the language says does not participate.
 */
final class StrayMemberLockTestCompileTest {
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

    private final BuildService buildService = new BuildService();
    private final TestCompileService testCompileService = new TestCompileService();
    private final BuildFingerprintService fingerprints = new BuildFingerprintService();

    @TempDir
    private Path tempDir;

    @Test
    void strayMemberLockDoesNotChangeTestFingerprint() throws IOException {
        Workspace workspace = workspace();

        TestCompileResult first = compile(workspace, "apps/api", "api");
        assertFalse(first.testCompilationSkipped(), "the first test compile runs");
        String fingerprintBefore = fingerprints.storedTestInputsFingerprintSha256(
                workspace.testOutput("apps/api"));

        workspace.plantMemberLock("apps/api", POISONED_MEMBER_LOCK);
        TestCompileResult second = compile(workspace, "apps/api", "api");

        assertTrue(
                second.testCompilationSkipped(),
                () -> "a member-local zolt.lock is not a test-compile input; got "
                        + second.testIncrementalFallbackReason());
        assertEquals(
                fingerprintBefore,
                fingerprints.storedTestInputsFingerprintSha256(workspace.testOutput("apps/api")),
                "the test-compile fingerprint is unchanged by a file no command may read");
    }

    /**
     * A directory where a member lock would be: unreadable as a lockfile by construction, and after
     * {@code chmod 000} unreadable as anything at all. Planted in BOTH members, because the failure
     * mode that matters is a workspace build tripping over one member's irrelevant path.
     */
    @Test
    void unreadableMemberLockCannotBreakWorkspaceBuild() throws IOException {
        assumeTrue(
                FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "needs POSIX permissions to make a directory unreadable");
        Workspace workspace = workspace();
        compile(workspace, "apps/api", "api");
        compile(workspace, "modules/core", "core");

        Path apiLock = workspace.plantUnreadableMemberLockDirectory("apps/api");
        workspace.plantUnreadableMemberLockDirectory("modules/core");
        assumeTrue(!Files.isReadable(apiLock), "test must not run as a user that ignores permissions");

        TestCompileResult api = assertDoesNotThrow(
                () -> compile(workspace, "apps/api", "api"),
                "an unreadable member-local path must not be able to fail a member build");
        TestCompileResult core = assertDoesNotThrow(
                () -> compile(workspace, "modules/core", "core"),
                "an unreadable member-local path must not be able to fail a member build");

        assertTrue(api.buildResult().mainCompilationSkipped(), "apps/api main output stays current");
        assertTrue(api.testCompilationSkipped(), "apps/api test output stays current");
        assertTrue(core.buildResult().mainCompilationSkipped(), "modules/core main output stays current");
        assertTrue(core.testCompilationSkipped(), "modules/core test output stays current");
    }

    private TestCompileResult compile(Workspace workspace, String memberPath, String name) {
        ProjectBuildContext context = workspace.context(memberPath);
        ProjectConfig config = workspace.config(name);
        ClasspathSet classpaths = classpaths();
        BuildResult buildResult = buildService.build(context, config, classpaths, List.of());
        return testCompileService.compileTests(context, config, classpaths, buildResult, List.of());
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
            write(directory, "src/main/java/" + packageName.replace('.', '/') + "/Component.java", """
                    package %s;

                    public final class Component {
                        public static String name() {
                            return "%s";
                        }
                    }
                    """.formatted(packageName, name));
            write(directory, "src/test/java/" + packageName.replace('.', '/') + "/ComponentTest.java", """
                    package %s;

                    final class ComponentTest {
                        boolean named() {
                            return Component.name() != null;
                        }
                    }
                    """.formatted(packageName));
        }

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

        Path testOutput(String memberPath) {
            return root.resolve(memberPath).resolve("target/test-classes");
        }

        void plantMemberLock(String memberPath, String content) throws IOException {
            Files.writeString(root.resolve(memberPath).resolve("zolt.lock"), content);
        }

        Path plantUnreadableMemberLockDirectory(String memberPath) throws IOException {
            Path lock = root.resolve(memberPath).resolve("zolt.lock");
            Files.createDirectories(lock);
            Files.writeString(lock.resolve("not-a-lockfile"), "unreachable\n");
            Files.setPosixFilePermissions(lock, PosixFilePermissions.fromString("---------"));
            return lock;
        }

        private static void write(Path directory, String relative, String content) throws IOException {
            Path path = directory.resolve(relative);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content);
        }
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0]) ? parts[1] : parts[0];
    }
}
