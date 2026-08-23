package sh.zolt.arch;

import static sh.zolt.arch.ArchitectureDiagnostics.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Guards the API SHAPE of lock ownership: not how a {@code zolt.lock} path is spelled, but WHO is
 * allowed to ask for one.
 *
 * <p>{@link LockfilePathOwnershipGuardrailTest} already forces every path derivation through
 * {@link sh.zolt.lockfile.ProjectLockfile}. That is necessary and not sufficient: the seam cannot tell
 * {@code ProjectLockfile.in(workspaceRoot)} from {@code ProjectLockfile.in(memberDirectory)}, and both
 * compile. The second one is the whole bug — design §4.5 makes a member-local {@code zolt.lock}
 * observationally irrelevant, and a core service that derives its own path reads the wrong file while
 * looking entirely reasonable at the call site.
 *
 * <p>So the rule this test enforces is structural rather than textual: a core operation RECEIVES the
 * authoritative lockfile path — through {@link sh.zolt.project.ProjectBuildContext}, or as an explicit
 * {@code lockfilePath} parameter — and never derives it. Calling {@code ProjectLockfile.in} is legal
 * only where lock ownership is first established: the command boundary, workspace root handling, the
 * resolve writer, and standalone entry points. Every such file is listed, with the reason it owns the
 * decision, in {@value #ALLOWLIST_RESOURCE}.
 *
 * <p>Entries are pruned, not accumulated: a listed file that stops calling {@code ProjectLockfile.in}
 * fails this test. That is how the transitional entries carrying the reason
 * {@value #TRANSITIONAL_REASON} get removed once the member-projection wave lands — the guardrail
 * fails until someone deletes them.
 */
final class ProjectLockfileCallerGuardrailTest {
    /** The seam itself owns the derivation; it is not a caller. */
    private static final String SEAM =
            "modules/zolt-model/src/main/java/sh/zolt/lockfile/ProjectLockfile.java";

    static final String ALLOWLIST_RESOURCE =
            "apps/zolt/src/test/resources/sh/zolt/arch/projectlockfile-caller-allowlist.txt";

    /** The reason the concurrent member-projection wave's derivation sites carry until it lands. */
    static final String TRANSITIONAL_REASON = "derives ahead of the member-projection adoption";

    /** {@code ProjectLockfile.in(...)}, including across a line break. */
    private static final Pattern CALL =
            Pattern.compile("\\bProjectLockfile\\s*\\.\\s*in\\s*\\(");

    /** A static import that would let {@code in(dir)} hide the call from the pattern above. */
    private static final Pattern STATIC_IMPORT = Pattern.compile(
            "import\\s+static\\s+sh\\s*\\.\\s*zolt\\s*\\.\\s*lockfile\\s*\\.\\s*ProjectLockfile\\s*\\.\\s*in\\s*;");

    @Test
    void onlyOwnershipBoundariesCallProjectLockfileIn() throws IOException {
        Map<String, String> allowlist = readAllowlist();
        List<String> violations = new ArrayList<>();
        List<String> callingFiles = new ArrayList<>();

        for (Path javaFile : ArchitectureSourceFiles.javaFiles(RepositoryPaths.mainSourceRoots())) {
            String display = RepositoryPaths.displayPath(javaFile);
            if (SEAM.equals(display)) {
                continue;
            }
            List<String> found = calls(display, Files.readString(javaFile));
            if (found.isEmpty()) {
                continue;
            }
            callingFiles.add(display);
            if (!allowlist.containsKey(display)) {
                violations.addAll(found);
            }
        }
        allowlist.keySet().stream()
                .filter(path -> !callingFiles.contains(path))
                .sorted()
                .forEach(path -> violations.add(
                        path + " no longer calls ProjectLockfile.in; remove the allowlist entry"));

        assertTrue(
                violations.isEmpty(),
                () -> "Lockfile ownership violations:\n"
                        + describe(violations)
                        + "\nA core build, test, or package operation RECEIVES the authoritative lockfile "
                        + "path — as a ProjectBuildContext or an explicit lockfilePath parameter — and never "
                        + "derives one from a project directory (design §4.5). Deriving it turns a workspace "
                        + "member's irrelevant local zolt.lock into a real input. If this file genuinely "
                        + "establishes lock ownership, add it to " + ALLOWLIST_RESOURCE + " with the reason.");
    }

    /** The whole point of the allowlist: every entry says which directory it decided owns the lock. */
    @Test
    void allowlistEntriesAreRealFilesWithReasons() throws IOException {
        Map<String, String> allowlist = readAllowlist();

        assertFalse(allowlist.isEmpty(), "the ownership boundaries are listed, not inferred");
        for (Map.Entry<String, String> entry : allowlist.entrySet()) {
            assertTrue(
                    Files.isRegularFile(RepositoryPaths.root().resolve(entry.getKey())),
                    () -> "Allowlisted file does not exist: " + entry.getKey());
            assertTrue(
                    entry.getValue().length() > 20,
                    () -> "Allowlist entry needs a real reason: " + entry.getKey());
        }
    }

    /**
     * The prescribed core chain is clean. Naming these files is the point: they are the ones the
     * defect lived in, so a future edit that reintroduces a derivation there fails here by name rather
     * than in the general sweep above.
     */
    @Test
    void coreBuildAndTestOperationsDoNotCallProjectLockfileIn() throws IOException {
        List<String> core = List.of(
                "modules/zolt-build/src/main/java/sh/zolt/build/BuildService.java",
                "modules/zolt-build/src/main/java/sh/zolt/build/BuildClasspathResolver.java",
                "modules/zolt-build/src/main/java/sh/zolt/build/GeneratedSourceToolingGate.java",
                "modules/zolt-build/src/main/java/sh/zolt/build/packaging/PackageService.java",
                "modules/zolt-build/src/main/java/sh/zolt/build/packaging/PackageTestCompileGate.java",
                "modules/zolt-build/src/main/java/sh/zolt/build/packaging/PackagePlanResolver.java",
                "modules/zolt-build/src/main/java/sh/zolt/build/packaging/PackageArchiveModePackager.java",
                "modules/zolt-build/src/main/java/sh/zolt/build/packaging/layout/ThinJarLayoutAssembler.java",
                "modules/zolt-build/src/main/java/sh/zolt/build/springboot/SpringBootPackageToolingPreparer.java",
                "modules/zolt-test-runtime/src/main/java/sh/zolt/build/testruntime/compile/TestCompileService.java",
                "modules/zolt-workspace/src/main/java/sh/zolt/workspace/service/WorkspaceMemberBuildExecutor.java",
                "modules/zolt-workspace/src/main/java/sh/zolt/workspace/service/WorkspaceBuildPlanner.java",
                "modules/zolt-workspace/src/main/java/sh/zolt/workspace/test/WorkspaceTestCompileExecutor.java",
                "modules/zolt-workspace/src/main/java/sh/zolt/workspace/test/WorkspaceTestTasks.java",
                "modules/zolt-workspace/src/main/java/sh/zolt/workspace/packaging/WorkspacePackageService.java");
        Map<String, String> allowlist = readAllowlist();
        List<String> violations = new ArrayList<>();

        for (String display : core) {
            Path file = RepositoryPaths.root().resolve(display);
            assertTrue(Files.isRegularFile(file), () -> "Core operation moved or was renamed: " + display);
            violations.addAll(calls(display, Files.readString(file)));
            if (allowlist.containsKey(display)) {
                violations.add(display + " is a core operation and must never be allowlisted");
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Core operations must receive the authoritative lockfile path:\n" + describe(violations));
    }

    /** SelfHostingCheckService keeps its standalone convenience overloads but must not check with them. */
    @Test
    void selfHostingCheckAcceptsAnExplicitLockfilePath() throws IOException {
        Path service = RepositoryPaths.root().resolve(
                "modules/zolt-build/src/main/java/sh/zolt/doctor/SelfHostingCheckService.java");
        Path doctor = RepositoryPaths.root().resolve(
                "apps/zolt/src/main/java/sh/zolt/cli/command/quality/DoctorCommand.java");

        String source = Files.readString(service);
        assertTrue(
                source.contains("check(Path projectDirectory, Path lockfilePath, ProjectConfig config)"),
                "SelfHostingCheckService takes the authoritative lockfile path explicitly");
        assertTrue(
                source.contains("Files.isRegularFile(lockfilePath)"),
                "the lockfile check reads the path it was handed");
        assertTrue(
                Files.readString(doctor).contains("context.lockfilePath()"),
                "DoctorCommand --self-hosting passes the command boundary's lockfile path");
    }

    @Test
    void scannerFlagsCallsButNotProseOrJavadocReferences(@TempDir Path tempDir) throws IOException {
        Path caller = tempDir.resolve("Caller.java");
        Files.writeString(caller, """
                class Caller {
                    Path direct = ProjectLockfile.in(projectDirectory);
                    Path spaced = ProjectLockfile . in ( root );
                }
                """);
        Path staticImporter = tempDir.resolve("StaticImporter.java");
        Files.writeString(staticImporter, """
                import static sh.zolt.lockfile.ProjectLockfile.in;

                class StaticImporter {
                    Path hidden = in(projectDirectory);
                }
                """);
        Path receiver = tempDir.resolve("Receiver.java");
        Files.writeString(receiver, """
                /** Never calls ProjectLockfile.in(projectDirectory); see {@link ProjectLockfile#in(Path)}. */
                class Receiver {
                    // ProjectLockfile.in(projectDirectory) would be the bug.
                    private static final String HINT = "call ProjectLockfile.in(lockRoot) at the boundary";

                    Path lockfile(ProjectBuildContext context) {
                        return context.lockfilePath();
                    }
                }
                """);

        assertEquals(2, calls("Caller.java", Files.readString(caller)).size());
        assertEquals(1, calls("StaticImporter.java", Files.readString(staticImporter)).size());
        assertTrue(calls("Receiver.java", Files.readString(receiver)).isEmpty());
    }

    @Test
    void allowlistParserRejectsAMissingReason(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("allowlist.txt"), "some/File.java|\n");

        assertThrows(IllegalStateException.class,
                () -> ArchGuardrailSupport.pathAllowlist(tempDir, "allowlist.txt"));
    }

    /** A stale entry — a listed file that no longer calls the seam — is reported, not ignored. */
    @Test
    void staleAllowlistEntriesAreReported() {
        List<String> allowlisted = List.of("modules/zolt-model/src/main/java/sh/zolt/project/ProjectBuildContext.java");
        List<String> callingFiles = List.of();
        List<String> violations = new ArrayList<>();

        allowlisted.stream()
                .filter(path -> !callingFiles.contains(path))
                .forEach(path -> violations.add(
                        path + " no longer calls ProjectLockfile.in; remove the allowlist entry"));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).endsWith("remove the allowlist entry"));
    }

    /**
     * The member-projection wave is complete: every derivation site either became a permanent
     * ownership boundary with its own reason or stopped deriving. The transitional marker exists
     * only in this test now, as a tombstone — an entry carrying it means someone reopened the
     * transition without finishing it.
     */
    @Test
    void noTransitionalEntriesRemain() throws IOException {
        Map<String, String> allowlist = readAllowlist();

        List<String> transitional = allowlist.entrySet().stream()
                .filter(entry -> entry.getValue().contains(TRANSITIONAL_REASON))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        assertTrue(
                transitional.isEmpty(),
                () -> "The member-projection wave completed; these entries must carry a permanent"
                        + " ownership reason or be removed: " + transitional);
    }

    private static List<String> calls(String display, String source) {
        String code = JavaSourceCode.withoutCommentsAndStrings(source);
        List<String> violations = new ArrayList<>();
        CALL.matcher(code).results().forEach(ignored ->
                violations.add(display + " calls ProjectLockfile.in to derive a lockfile path"));
        if (STATIC_IMPORT.matcher(code).find()) {
            violations.add(display + " statically imports ProjectLockfile.in");
        }
        return violations;
    }

    private static Map<String, String> readAllowlist() throws IOException {
        return ArchGuardrailSupport.pathAllowlist(RepositoryPaths.root(), ALLOWLIST_RESOURCE).entries();
    }
}
