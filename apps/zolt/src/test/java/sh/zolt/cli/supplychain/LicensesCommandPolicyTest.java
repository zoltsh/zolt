package sh.zolt.cli.supplychain;

import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code zolt licenses} reports what the configured license policy makes of each dependency, and
 * points at the command that enforces it. Enforcement itself stays in {@code zolt check --check
 * license-policy}, so the exit code here is always 0.
 */
final class LicensesCommandPolicyTest {
    @TempDir
    private Path tempDir;

    @Test
    void deniedDependencyIsAnnotatedAndSummarizedWithoutFailingTheCommand() throws IOException {
        Path projectDir = tempDir.resolve("denied");
        Path cache = tempDir.resolve("cache");
        writeProject(projectDir, """

                [dependencyPolicy.licenses]
                deny = ["GPL-3.0-only"]
                """);
        writePom(cache, "GPL-3.0-only");

        CommandResult result = execute("licenses", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(
                result.stdout().contains(
                        "org.example:lib:1.0.0  [denied] denied by [dependencyPolicy.licenses].deny"),
                result.stdout());
        assertTrue(result.stdout().contains("License policy: 1 denied, 0 unknown of 1 dependency."), result.stdout());
        assertTrue(
                result.stdout().contains("Next: run `zolt check --check license-policy` to enforce it."),
                result.stdout());
    }

    @Test
    void unknownLicenseIsAnnotatedUnderAnAllowList() throws IOException {
        Path projectDir = tempDir.resolve("unknown");
        Path cache = tempDir.resolve("cache-unknown");
        writeProject(projectDir, """

                [dependencyPolicy.licenses]
                allow = ["Apache-2.0"]
                unknown = "warn"
                """);
        writePomWithoutLicense(cache);

        CommandResult result = execute("licenses", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());

        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("[unknown] unrecognized license"), result.stdout());
        assertTrue(result.stdout().contains("License policy: 0 denied, 1 unknown of 1 dependency."), result.stdout());
    }

    @Test
    void jsonModeAddsPolicyFieldsAdditively() throws IOException {
        Path projectDir = tempDir.resolve("json");
        Path cache = tempDir.resolve("cache-json");
        writeProject(projectDir, """

                [dependencyPolicy.licenses]
                deny = ["GPL-3.0-only"]
                """);
        writePom(cache, "GPL-3.0-only");

        CommandResult result = execute("licenses", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString(), "--format", "json");

        assertEquals(0, result.exitCode(), result.stderr());
        // Existing fields are untouched.
        assertTrue(result.stdout().contains("\"schemaVersion\": 1"), result.stdout());
        assertTrue(result.stdout().contains("\"license\": \"GPL-3.0-only\""), result.stdout());
        assertTrue(result.stdout().contains("\"coordinate\": \"org.example:lib:1.0.0\""), result.stdout());
        // New fields are added alongside them.
        assertTrue(result.stdout().contains("\"policy\": {"), result.stdout());
        assertTrue(result.stdout().contains("\"status\": \"denied\""), result.stdout());
        assertTrue(result.stdout().contains("\"enforcedBy\": \"zolt check --check license-policy\""), result.stdout());
    }

    @Test
    void scopedExpressionExceptionIsReportedAndEnforcedByTheSameDecision() throws IOException {
        Path projectDir = tempDir.resolve("exception");
        Path cache = tempDir.resolve("cache-exception");
        writeProject(projectDir, """

                [dependencyPolicy.licenses]
                allow = ["MIT"]
                unknown = "fail"

                [dependencyPolicy.licenses.exceptions."org.example:lib"]
                allow = ["BSD-3-Clause"]
                version = "1.0.0"
                reason = "Reviewed transitive expression"
                """);
        writePom(cache, "MIT AND BSD-3-Clause");

        CommandResult licenses = execute("licenses", "--format", "json", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());
        CommandResult check = execute("check", "--check", "license-policy", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());

        assertEquals(0, licenses.exitCode(), licenses.stderr());
        assertTrue(licenses.stdout().contains("\"expression\": \"MIT AND BSD-3-Clause\""), licenses.stdout());
        assertTrue(licenses.stdout().contains("\"status\": \"permitted-by-exception\""), licenses.stdout());
        assertTrue(licenses.stdout().contains("\"reason\": \"Reviewed transitive expression\""), licenses.stdout());
        assertEquals(0, check.exitCode(), check.stdout() + check.stderr());
    }

    @Test
    void staleExceptionIsReportedWithoutFailingLicensesAndFailsTheCheck() throws IOException {
        Path projectDir = tempDir.resolve("stale-exception");
        Path cache = tempDir.resolve("cache-stale-exception");
        writeProject(projectDir, """

                [dependencyPolicy.licenses]
                allow = ["MIT"]

                [dependencyPolicy.licenses.exceptions."org.example:missing"]
                allow = ["BSD-3-Clause"]
                version = "1.0.0"
                reason = "Old review"
                """);
        writePom(cache, "MIT");

        CommandResult licenses = execute("licenses", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());
        CommandResult check = execute("check", "--check", "license-policy", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());

        assertEquals(0, licenses.exitCode(), licenses.stderr());
        assertTrue(licenses.stdout().contains("org.example:missing@1.0.0  [missing]"), licenses.stdout());
        assertTrue(licenses.stdout().contains("1 stale exception"), licenses.stdout());
        assertEquals(1, check.exitCode(), check.stdout() + check.stderr());
        assertTrue(check.stdout().contains(
                "[dependencyPolicy.licenses.exceptions.\"org.example:missing\"]"), check.stdout());
    }

    @Test
    void withoutAPolicyTheOutputCarriesNoPolicyAnnotations() throws IOException {
        Path projectDir = tempDir.resolve("unannotated");
        Path cache = tempDir.resolve("cache-unannotated");
        writeProject(projectDir, "");
        writePom(cache, "GPL-3.0-only");

        CommandResult text = execute("licenses", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());
        CommandResult json = execute("licenses", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString(), "--format", "json");

        assertEquals(0, text.exitCode(), text.stderr());
        assertEquals("""
                GPL-3.0-only (1)
                  org.example:lib:1.0.0
                """, text.stdout());
        assertFalse(json.stdout().contains("licensePolicy"), json.stdout());
        assertFalse(json.stdout().contains("\"policy\""), json.stdout());
    }

    /**
     * The annotation names {@code zolt check --check license-policy} as its enforcer, and that command
     * evaluates compile/runtime only. A test-scoped dependency a wider report lists therefore stays
     * listed and stays unannotated — asserted alongside the enforcing command passing on the same
     * fixture, because the two surfaces disagreeing is the whole defect.
     */
    @Test
    void aTestScopedDependencyStaysListedButUnannotatedAndTheEnforcingCommandAgrees() throws IOException {
        Path projectDir = tempDir.resolve("test-scope");
        Path cache = tempDir.resolve("cache-test-scope");
        writeProject(projectDir, """

                [dependencyPolicy.licenses]
                deny = ["GPL-3.0-only"]
                """, lockPackage("lib", "compile") + lockPackage("test-lib", "test"));
        writePom(cache, "lib", "Apache-2.0");
        writePom(cache, "test-lib", "GPL-3.0-only");

        CommandResult text = execute("licenses", "--include-test", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());
        CommandResult json = execute("licenses", "--include-test", "--format", "json",
                "--cwd", projectDir.toString(), "--cache-root", cache.toString());
        CommandResult check = execute("check", "--check", "license-policy", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());

        assertEquals(0, text.exitCode(), text.stderr());
        // Listed by the report the user asked for...
        assertTrue(text.stdout().contains("org.example:test-lib:1.0.0"), text.stdout());
        // ...but never marked, and never counted.
        assertFalse(text.stdout().contains("[denied]"), text.stdout());
        assertTrue(text.stdout().contains("License policy: 0 denied, 0 unknown of 1 dependency."), text.stdout());
        assertFalse(json.stdout().contains("\"policy\""), json.stdout());
        assertTrue(json.stdout().contains("\"evaluated\": 1"), json.stdout());
        // The command the report points at agrees: nothing to enforce.
        assertEquals(0, check.exitCode(), check.stdout() + check.stderr());
    }

    /** In both an enforced and an optional scope means enforced: the coordinate is annotated. */
    @Test
    void aCoordinateInBothCompileAndTestScopeIsAnnotated() throws IOException {
        Path projectDir = tempDir.resolve("both-scopes");
        Path cache = tempDir.resolve("cache-both-scopes");
        writeProject(projectDir, """

                [dependencyPolicy.licenses]
                deny = ["GPL-3.0-only"]
                """, lockPackage("lib", "compile") + lockPackage("lib", "test"));
        writePom(cache, "lib", "GPL-3.0-only");

        CommandResult text = execute("licenses", "--include-test", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());
        CommandResult check = execute("check", "--check", "license-policy", "--cwd", projectDir.toString(),
                "--cache-root", cache.toString());

        assertEquals(0, text.exitCode(), text.stderr());
        assertTrue(
                text.stdout().contains(
                        "org.example:lib:1.0.0  [denied] denied by [dependencyPolicy.licenses].deny"),
                text.stdout());
        assertTrue(text.stdout().contains("License policy: 1 denied, 0 unknown of 1 dependency."), text.stdout());
        // And again the enforcing command agrees — this time by failing.
        assertEquals(1, check.exitCode(), check.stdout() + check.stderr());
    }

    /** The denominator is the enforcing closure, not the report: optional-scope extras never inflate it. */
    @Test
    void theSummaryCountsOnlyTheCoordinatesInTheEnforcingScope() throws IOException {
        Path projectDir = tempDir.resolve("denominator");
        Path cache = tempDir.resolve("cache-denominator");
        writeProject(projectDir, """

                [dependencyPolicy.licenses]
                deny = ["GPL-3.0-only"]
                """,
                lockPackage("lib", "compile")
                        + lockPackage("runtime-lib", "runtime")
                        + lockPackage("test-lib", "test")
                        + lockPackage("provided-lib", "provided"));
        writePom(cache, "lib", "Apache-2.0");
        writePom(cache, "runtime-lib", "Apache-2.0");
        writePom(cache, "test-lib", "Apache-2.0");
        writePom(cache, "provided-lib", "Apache-2.0");

        CommandResult text = execute("licenses", "--include-test", "--include-provided",
                "--cwd", projectDir.toString(), "--cache-root", cache.toString());

        assertEquals(0, text.exitCode(), text.stderr());
        assertTrue(text.stdout().contains("Apache-2.0 (4)"), text.stdout());
        // Four listed, two enforced: compile and runtime.
        assertTrue(text.stdout().contains("License policy: 0 denied, 0 unknown of 2 dependencies."), text.stdout());
    }

    private static void writeProject(Path projectDir, String policy) throws IOException {
        writeProject(projectDir, policy, lockPackage("lib", "compile"));
    }

    private static void writeProject(Path projectDir, String policy, String packages) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), memberConfig("demo") + policy);
        Files.writeString(projectDir.resolve("zolt.lock"), """
                version = 1
                projectResolutionFingerprint = "sha256:cli-licenses-policy"
                """ + packages);
    }

    private static String lockPackage(String artifact, String scope) {
        return """

                [[package]]
                id = "org.example:%1$s"
                version = "1.0.0"
                source = "maven-central"
                scope = "%2$s"
                direct = true
                jar = "org/example/%1$s/1.0.0/%1$s-1.0.0.jar"
                pom = "org/example/%1$s/1.0.0/%1$s-1.0.0.pom"
                jarSha256 = "1111111111111111111111111111111111111111111111111111111111111111"
                dependencies = []
                """.formatted(artifact, scope);
    }

    private static void writePom(Path cache, String licenseName) throws IOException {
        writePom(cache, "lib", licenseName);
    }

    private static void writePom(Path cache, String artifact, String licenseName) throws IOException {
        writePomBody(
                cache, artifact, "  <licenses><license><name>" + licenseName + "</name></license></licenses>\n");
    }

    private static void writePomWithoutLicense(Path cache) throws IOException {
        writePomBody(cache, "lib", "");
    }

    private static void writePomBody(Path cache, String artifact, String licenses) throws IOException {
        Path pom = cache.resolve("org/example/" + artifact + "/1.0.0/" + artifact + "-1.0.0.pom");
        Files.createDirectories(pom.getParent());
        Files.writeString(pom, """
                <project>
                  <groupId>org.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                %s</project>
                """.formatted(artifact, licenses));
    }
}
