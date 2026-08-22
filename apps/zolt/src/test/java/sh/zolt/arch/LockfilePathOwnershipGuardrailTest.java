package sh.zolt.arch;

import static sh.zolt.arch.ArchitectureDiagnostics.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the one seam that turns a directory into a {@code zolt.lock} path.
 *
 * <p>Design §4.5/§6.8: a workspace has exactly one authoritative lockfile, at its root, and no
 * command creates or consumes a member-local one. The way that rule breaks is never a misspelled
 * file name — it is the RIGHT name resolved against the WRONG directory, so a command started in
 * {@code apps/api} quietly reads or writes {@code apps/api/zolt.lock}. Every such site reads
 * plausibly on its own; only the parameter it resolves against is wrong.
 *
 * <p>So the path derivation lives in exactly one place, {@link sh.zolt.lockfile.ProjectLockfile},
 * whose parameter is named {@code lockRoot}: a caller cannot ask for the path without having decided
 * which directory owns it, and the command boundary ({@code ProjectCommandContext}) decides that once
 * and passes the answer down.
 *
 * <p>Naming {@code zolt.lock} in a diagnostic, a report label, or a plan's input list is not path
 * derivation and stays legal — this guard is about {@code resolve}, not about prose.
 */
final class LockfilePathOwnershipGuardrailTest {
    /** The seam itself: the single legal place where a directory and the lock file name meet. */
    private static final String SEAM =
            "modules/zolt-model/src/main/java/sh/zolt/lockfile/ProjectLockfile.java";

    private static final Path ALLOWLIST = RepositoryPaths.appRoot()
            .resolve("src/test/resources/sh/zolt/arch/lockfile-path-allowlist.txt");

    /** {@code <anything>.resolve("zolt.lock")}, including across a line break. */
    private static final Pattern RESOLVED_LITERAL =
            Pattern.compile("\\.\\s*resolve\\s*\\(\\s*\"zolt\\.lock\"\\s*\\)");

    /** A file-local constant holding the file name, which {@code resolve} then hides behind. */
    private static final Pattern LOCKFILE_CONSTANT =
            Pattern.compile("\\b(?:String\\s+)?([A-Z][A-Z0-9_]*)\\s*=\\s*\"zolt\\.lock\"\\s*;");

    /** {@code Path.of(..., "zolt.lock")} and {@code Paths.get(..., "zolt.lock")}. */
    private static final Pattern PATH_FACTORY = Pattern.compile(
            "(?:Path\\s*\\.\\s*of|Paths\\s*\\.\\s*get)\\s*\\([^;]{0,200}?\"zolt\\.lock\"");

    @Test
    void mainSourcesDeriveTheLockfilePathThroughTheSeam() throws IOException {
        Map<String, String> allowlist = readAllowlist();
        List<String> violations = new ArrayList<>();
        List<String> derivingFiles = new ArrayList<>();

        for (Path javaFile : ArchitectureSourceFiles.javaFiles(RepositoryPaths.mainSourceRoots())) {
            String display = RepositoryPaths.displayPath(javaFile);
            if (SEAM.equals(display)) {
                continue;
            }
            List<String> found = derivations(display, Files.readString(javaFile));
            if (found.isEmpty()) {
                continue;
            }
            derivingFiles.add(display);
            if (!allowlist.containsKey(display)) {
                violations.addAll(found);
            }
        }
        allowlist.keySet().stream()
                .filter(path -> !derivingFiles.contains(path))
                .sorted()
                .forEach(path -> violations.add(
                        path + " no longer derives a zolt.lock path; remove the allowlist entry"));

        assertTrue(
                violations.isEmpty(),
                () -> "Lockfile path ownership violations:\n"
                        + describe(violations)
                        + "\nCall sh.zolt.lockfile.ProjectLockfile.in(lockRoot) with the directory that "
                        + "owns the lock — for a workspace member that is the workspace root, never the "
                        + "member directory (design §4.5/§6.8).");
    }

    @Test
    void theSeamIsPresentAndOwnsTheFileName() throws IOException {
        Path seam = RepositoryPaths.root().resolve(SEAM);

        assertTrue(Files.isRegularFile(seam), () -> "Missing lockfile path seam at " + SEAM);
        String source = Files.readString(seam);
        assertTrue(source.contains("\"zolt.lock\""), "the seam owns the file name");
        assertTrue(source.contains("lockRoot"), "the seam names its parameter lockRoot");
    }

    @Test
    void scannerFlagsDerivationsButNotProseOrReportLabels(@TempDir Path tempDir) throws IOException {
        Path offender = tempDir.resolve("Offender.java");
        Files.writeString(offender, """
                class Offender {
                    Path direct = projectDirectory.resolve("zolt.lock");
                    Path wrapped = lockfileDirectory
                            .normalize()
                            .resolve("zolt.lock");
                    Path built = Path.of(root.toString(), "zolt.lock");
                }
                """);
        Path constantOffender = tempDir.resolve("ConstantOffender.java");
        Files.writeString(constantOffender, """
                class ConstantOffender {
                    private static final String LOCKFILE = "zolt.lock";

                    Path lockfile = projectRoot.resolve(LOCKFILE);
                }
                """);
        Path reporter = tempDir.resolve("Reporter.java");
        Files.writeString(reporter, """
                class Reporter {
                    List<String> inputs = List.of(output, "zolt.lock");
                    String problem = "zolt.lock is out of date. Run `zolt resolve`.";
                    Path lockfile = ProjectLockfile.in(lockRoot);
                }
                """);

        assertEquals(3, derivations("Offender.java", Files.readString(offender)).size());
        assertEquals(2, derivations("ConstantOffender.java", Files.readString(constantOffender)).size());
        assertTrue(derivations("Reporter.java", Files.readString(reporter)).isEmpty());
    }

    @Test
    void allowlistParserRejectsAMissingReason(@TempDir Path tempDir) throws IOException {
        Path allowlist = tempDir.resolve("allowlist.txt");
        Files.writeString(allowlist, "some/File.java|\n");

        assertThrows(IllegalStateException.class,
                () -> ArchGuardrailSupport.pathAllowlist(tempDir, "allowlist.txt"));
    }

    /** Every allowlisted path exists and carries a non-empty reason. */
    @Test
    void allowlistEntriesAreRealFilesWithReasons() throws IOException {
        Map<String, String> allowlist = readAllowlist();

        assertTrue(!allowlist.isEmpty() || Files.isRegularFile(ALLOWLIST));
        for (Map.Entry<String, String> entry : allowlist.entrySet()) {
            assertTrue(
                    Files.isRegularFile(RepositoryPaths.root().resolve(entry.getKey())),
                    () -> "Allowlisted file does not exist: " + entry.getKey());
            assertTrue(
                    entry.getValue().length() > 20,
                    () -> "Allowlist entry needs a real reason: " + entry.getKey());
        }
    }

    private static List<String> derivations(String display, String source) {
        List<String> violations = new ArrayList<>();
        if (RESOLVED_LITERAL.matcher(source).find()) {
            RESOLVED_LITERAL.matcher(source).results().forEach(ignored ->
                    violations.add(display + " resolves the \"zolt.lock\" literal against a directory"));
        }
        constantName(source).ifPresent(name -> {
            violations.add(display + " declares a `zolt.lock` file-name constant");
            Pattern resolvedConstant =
                    Pattern.compile("\\.\\s*resolve\\s*\\(\\s*" + Pattern.quote(name) + "\\s*\\)");
            if (resolvedConstant.matcher(source).find()) {
                violations.add(display + " resolves " + name + " against a directory");
            }
        });
        PATH_FACTORY.matcher(source).results().forEach(ignored ->
                violations.add(display + " builds a path ending in \"zolt.lock\""));
        return violations;
    }

    /**
     * The name of a file-local constant whose value is exactly {@code "zolt.lock"}. The seam owns
     * that constant; anywhere else it exists to be resolved against something.
     */
    private static Optional<String> constantName(String source) {
        Matcher matcher = LOCKFILE_CONSTANT.matcher(source);
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private static Map<String, String> readAllowlist() throws IOException {
        return ArchGuardrailSupport.pathAllowlist(
                        RepositoryPaths.root(),
                        "apps/zolt/src/test/resources/sh/zolt/arch/lockfile-path-allowlist.txt")
                .entries();
    }
}
