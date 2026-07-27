package sh.zolt.arch;

import static sh.zolt.arch.ArchitectureDiagnostics.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the user-global directory seam. Every path that means "the {@code .zolt} user directory" —
 * {@code config.toml}, the artifact cache, the build-cache CAS, toolchains, shims, daemon runtime
 * state — must resolve through {@link sh.zolt.home.UserGlobalDirectory}, which is the one place that
 * honors {@code ZOLT_USER_HOME}.
 *
 * <p>Re-deriving the directory from {@code System.getProperty("user.home")} (or hard-coding a
 * {@code Path.of("~/.zolt/...")} literal) silently opts that one path out of the override, which is
 * exactly the split this seam removes: {@code zolt config show} reading the redirected config while
 * the artifact cache keeps writing under the real home. Reading {@code user.home} for something that
 * is genuinely the OS home stays legal — {@code ~/.m2/repository} and {@code ~} expansion of
 * user-written config values are unaffected, because neither names the {@code .zolt} directory.
 */
final class UserGlobalDirectoryGuardrailTest {
    /** The seam itself: the single legal place where user.home and `.zolt` meet. */
    private static final String SEAM = "modules/zolt-model/src/main/java/sh/zolt/home/UserGlobalDirectory.java";

    private static final Pattern USER_HOME_PROPERTY =
            Pattern.compile("System\\s*\\.\\s*getProperty\\s*\\(\\s*\"user\\.home\"");
    private static final Pattern ZOLT_DIRECTORY_LITERAL = Pattern.compile("\"\\.zolt\"");
    private static final Pattern HOME_RELATIVE_ZOLT_PATH = Pattern.compile("Path\\s*\\.\\s*of\\s*\\(\\s*\"~/\\.zolt");

    @Test
    void mainSourcesResolveTheUserDirectoryThroughTheSeam() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path javaFile : ArchitectureSourceFiles.javaFiles(RepositoryPaths.mainSourceRoots())) {
            String display = RepositoryPaths.displayPath(javaFile);
            if (SEAM.equals(display)) {
                continue;
            }
            violations.addAll(violations(display, Files.readString(javaFile)));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "User-global directory guardrail violations:\n"
                        + describe(violations)
                        + "\nResolve user-global paths through sh.zolt.home.UserGlobalDirectory so "
                        + "ZOLT_USER_HOME redirects the whole tree.");
    }

    @Test
    void theSeamIsPresentAndIsTheOnlyAllowance() throws IOException {
        Path seam = RepositoryPaths.root().resolve(SEAM);

        assertTrue(Files.isRegularFile(seam), () -> "Missing user-global directory seam at " + SEAM);
        String source = Files.readString(seam);
        assertTrue(source.contains("\"user.home\""), "the seam falls back to user.home");
        assertTrue(source.contains("ZOLT_USER_HOME"), "the seam names the override variable");
    }

    @Test
    void scannerFlagsReDerivedDirectoriesButNotGenuineHomeReads(@TempDir Path tempDir) throws IOException {
        Path offender = tempDir.resolve("Offender.java");
        Files.writeString(offender, """
                class Offender {
                    Path cache = Path.of(System.getProperty("user.home"), ".zolt", "cache");
                    Path buildCache = Path.of("~/.zolt/build-cache");
                }
                """);
        Path mavenLocal = tempDir.resolve("MavenLocal.java");
        Files.writeString(mavenLocal, """
                class MavenLocal {
                    Path repository = Path.of(System.getProperty("user.home"), ".m2", "repository");
                }
                """);
        Path projectState = tempDir.resolve("ProjectState.java");
        Files.writeString(projectState, """
                class ProjectState {
                    Path metadata = output.resolve(".zolt").resolve("exec");
                }
                """);

        assertEquals(2, violations("Offender.java", Files.readString(offender)).size());
        assertTrue(violations("MavenLocal.java", Files.readString(mavenLocal)).isEmpty());
        assertTrue(violations("ProjectState.java", Files.readString(projectState)).isEmpty());
        assertFalse(ZOLT_DIRECTORY_LITERAL.matcher("resolve(\".zolt-cache\")").find());
    }

    private static List<String> violations(String display, String source) {
        List<String> violations = new ArrayList<>();
        if (USER_HOME_PROPERTY.matcher(source).find() && ZOLT_DIRECTORY_LITERAL.matcher(source).find()) {
            violations.add(display + " re-derives the user-global `.zolt` directory from user.home; "
                    + "call sh.zolt.home.UserGlobalDirectory instead so ZOLT_USER_HOME applies.");
        }
        if (HOME_RELATIVE_ZOLT_PATH.matcher(source).find()) {
            violations.add(display + " hard-codes a `~/.zolt` path literal; "
                    + "call sh.zolt.home.UserGlobalDirectory instead so ZOLT_USER_HOME applies.");
        }
        return violations;
    }
}
