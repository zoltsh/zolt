package sh.zolt.home;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.error.ActionableException;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

final class UserGlobalDirectoryTest {
    private static final UnaryOperator<String> NO_ENVIRONMENT = name -> null;
    /** Filesystem root of the current platform, so "absolute" means the same thing on Windows. */
    private static final Path FILESYSTEM_ROOT = Path.of("").toAbsolutePath().getRoot();
    private static final Path USER_HOME = FILESYSTEM_ROOT.resolve("home").resolve("dev");
    private static final Path OVERRIDE = FILESYSTEM_ROOT.resolve("srv").resolve("ci").resolve("zolt-home");

    @Test
    void defaultsToTheDotZoltDirectoryUnderTheUserHome() {
        assertEquals(
                USER_HOME.resolve(".zolt"),
                UserGlobalDirectory.root(NO_ENVIRONMENT, properties(USER_HOME.toString())));
    }

    @Test
    void environmentOverrideWinsOverUserHome() {
        assertEquals(
                OVERRIDE,
                UserGlobalDirectory.root(environment(OVERRIDE.toString()), properties(USER_HOME.toString())));
    }

    @Test
    void overrideReplacesTheWholeTreeInsteadOfAppendingDotZolt() {
        Path root = UserGlobalDirectory.root(environment(OVERRIDE.toString()), properties(USER_HOME.toString()));

        assertEquals("zolt-home", root.getFileName().toString());
        assertEquals(OVERRIDE, root);
    }

    @Test
    void overrideIsTrimmedAndNormalized() {
        String noisy = "  "
                + FILESYSTEM_ROOT.resolve("srv").resolve("ci").resolve("nested").resolve("..").resolve("zolt-home")
                + "  ";

        assertEquals(OVERRIDE, UserGlobalDirectory.root(environment(noisy), properties(USER_HOME.toString())));
    }

    @Test
    void blankOverrideFallsBackToTheUserHome() {
        assertEquals(
                USER_HOME.resolve(".zolt"),
                UserGlobalDirectory.root(environment("   "), properties(USER_HOME.toString())));
    }

    @Test
    void emptyOverrideFallsBackToTheUserHome() {
        assertEquals(
                USER_HOME.resolve(".zolt"),
                UserGlobalDirectory.root(environment(""), properties(USER_HOME.toString())));
    }

    @Test
    void relativeOverrideIsRejectedByName() {
        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> UserGlobalDirectory.root(environment("relative/zolt-home"), properties(USER_HOME.toString())));

        assertEquals(
                "ZOLT_USER_HOME is set to the relative path `relative/zolt-home`, so user-global paths"
                        + " would move with the working directory.",
                exception.error().summary());
        assertTrue(exception.error().remediation().contains("absolute directory path"));
    }

    @Test
    void missingUserHomeIsRejectedAndPointsAtTheOverride() {
        ActionableException exception = assertThrows(
                ActionableException.class,
                () -> UserGlobalDirectory.root(NO_ENVIRONMENT, properties("")));

        assertTrue(exception.error().summary().contains("user-global Zolt directory"));
        assertTrue(exception.error().remediation().contains(UserGlobalDirectory.USER_HOME_ENV));
    }

    @Test
    void wellKnownChildrenHangOffTheResolvedRoot() {
        Path root = UserGlobalDirectory.root();

        assertEquals(root.resolve("config.toml"), UserGlobalDirectory.configFile());
        assertEquals(root.resolve("cache"), UserGlobalDirectory.artifactCache());
        assertEquals(root.resolve("build-cache"), UserGlobalDirectory.buildCache());
        assertEquals(root.resolve("toolchains"), UserGlobalDirectory.toolchains());
        assertEquals(root.resolve("shims"), UserGlobalDirectory.shims());
        assertEquals(root.resolve("run").resolve("javac"), UserGlobalDirectory.runtime("javac"));
    }

    @Test
    void environmentVariableIsTheDocumentedName() {
        assertEquals("ZOLT_USER_HOME", UserGlobalDirectory.USER_HOME_ENV);
    }

    private static UnaryOperator<String> environment(String userHome) {
        Map<String, String> values = Map.of(UserGlobalDirectory.USER_HOME_ENV, userHome);
        return values::get;
    }

    private static UnaryOperator<String> properties(String userHome) {
        Map<String, String> values = Map.of("user.home", userHome);
        return values::get;
    }
}
