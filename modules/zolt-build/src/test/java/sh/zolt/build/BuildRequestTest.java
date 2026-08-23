package sh.zolt.build;

import static sh.zolt.build.packaging.PackageServiceTestSupport.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.lockfile.ProjectBuildContext;
import sh.zolt.project.ProjectConfig;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class BuildRequestTest {
    private final Path projectDirectory = Path.of("project").toAbsolutePath().normalize();
    private final ProjectConfig config = config(Optional.empty());
    private final Path cacheRoot = Path.of("cache");

    @Test
    void keepsBuildInputsTogether() {
        BuildRequest request = new BuildRequest(projectDirectory, config, cacheRoot, true);

        assertEquals(projectDirectory, request.projectDirectory());
        assertEquals(config, request.config());
        assertEquals(cacheRoot, request.cacheRoot());
        assertTrue(request.offline());
    }

    /** A standalone project directory is its own lock root, so the two agree by construction. */
    @Test
    void standaloneRequestLocksAgainstItsOwnDirectory() {
        BuildRequest request = new BuildRequest(projectDirectory, config, cacheRoot, false);

        assertEquals(projectDirectory.resolve("zolt.lock"), request.lockfilePath());
    }

    /**
     * The reason this record carries a context instead of a directory: a member's authoritative lock
     * lives above its project directory, and the resolve lane must not be able to derive its own.
     */
    @Test
    void memberRequestLocksAgainstTheWorkspaceRootNotTheMemberDirectory() {
        Path workspaceRoot = Path.of("ws").toAbsolutePath().normalize();
        Path memberDirectory = workspaceRoot.resolve("apps/api");
        BuildRequest request = new BuildRequest(
                ProjectBuildContext.member(memberDirectory, workspaceRoot.resolve("zolt.lock"), "apps/api"),
                config,
                cacheRoot,
                false);

        assertEquals(memberDirectory, request.projectDirectory());
        assertEquals(workspaceRoot.resolve("zolt.lock"), request.lockfilePath());
        assertNotEquals(memberDirectory.resolve("zolt.lock"), request.lockfilePath());
    }

    @Test
    void requiresProjectDirectory() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new BuildRequest((ProjectBuildContext) null, config, cacheRoot, false));

        assertEquals("context", exception.getMessage());
    }

    @Test
    void requiresConfig() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new BuildRequest(projectDirectory, null, cacheRoot, false));

        assertEquals("config", exception.getMessage());
    }

    @Test
    void requiresCacheRoot() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new BuildRequest(projectDirectory, config, null, false));

        assertEquals("cacheRoot", exception.getMessage());
    }
}
