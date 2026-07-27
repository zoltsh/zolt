package sh.zolt.cli.supplychain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;
import static sh.zolt.cli.CliTestSupport.memberConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * {@code zolt licenses --workspace} annotations must agree with the command they point at.
 *
 * <p>{@code [dependencyPolicy]} is member-local, so a member's policy governs only that member's own
 * dependency closure — the same scoping {@code zolt check --workspace --check license-policy} enforces.
 * A report that flagged a coordinate the enforcing command passes would be worse than no report at all,
 * so these run both surfaces over one fixture.
 */
final class LicensesCommandWorkspacePolicyTest {
    private static final Pattern COORDINATE = Pattern.compile("\"coordinate\": \"([^\"]+)\"");
    private static final Pattern EVALUATED = Pattern.compile("\"evaluated\": (\\d+)");

    @TempDir
    private Path tempDir;

    @Test
    void aStrictMemberDoesNotDenyADependencyItDoesNotConsume() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addArtifacts(repository);
            // modules/core consumes the GPL library and configures no policy; apps/admin denies GPL but
            // never depends on it.
            Path workspace = writeWorkspace(
                    repository,
                    """

                    [dependencies]
                    "org.example:gpl-lib" = "1.0.0"
                    """,
                    """

                    [dependencies]
                    "org.example:mit-lib" = "1.0.0"

                    [dependencyPolicy.licenses]
                    deny = ["GPL-3.0-only"]
                    """);
            Path cache = tempDir.resolve("cache");
            assertEquals(0, resolve(workspace, cache).exitCode());

            CommandResult licenses = execute("licenses", "--workspace",
                    "--cwd", workspace.toString(), "--cache-root", cache.toString());
            CommandResult check = execute("check", "--workspace", "--check", "license-policy",
                    "--cwd", workspace.toString(), "--cache-root", cache.toString());

            assertEquals(0, licenses.exitCode(), licenses.stdout() + licenses.stderr());
            assertTrue(licenses.stdout().contains("org.example:gpl-lib:1.0.0"), licenses.stdout());
            assertFalse(licenses.stdout().contains("[denied]"), licenses.stdout());
            assertTrue(licenses.stdout().contains("License policy: 0 denied, 0 unknown of 2 dependencies."),
                    licenses.stdout());
            // The command the report names as the enforcer agrees.
            assertEquals(0, check.exitCode(), check.stdout() + check.stderr());
            assertFalse(check.stdout().contains("error license-policy"), check.stdout());
        }
    }

    @Test
    void aCoordinateTwoMembersConsumeKeepsTheStricterVerdict() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addArtifacts(repository);
            // Both members depend on the same GPL library; one allows it, the other denies it.
            Path workspace = writeWorkspace(
                    repository,
                    """

                    [dependencies]
                    "org.example:gpl-lib" = "1.0.0"

                    [dependencyPolicy.licenses]
                    allow = ["GPL-3.0-only"]
                    """,
                    """

                    [dependencies]
                    "org.example:gpl-lib" = "1.0.0"

                    [dependencyPolicy.licenses]
                    deny = ["GPL-3.0-only"]
                    """);
            Path cache = tempDir.resolve("cache");
            assertEquals(0, resolve(workspace, cache).exitCode());

            CommandResult licenses = execute("licenses", "--workspace",
                    "--cwd", workspace.toString(), "--cache-root", cache.toString());

            assertEquals(0, licenses.exitCode(), licenses.stdout() + licenses.stderr());
            assertTrue(licenses.stdout().contains(
                    "org.example:gpl-lib:1.0.0  [denied] denied by [dependencyPolicy.licenses].deny"),
                    licenses.stdout());
            assertTrue(licenses.stdout().contains("License policy: 1 denied, 0 unknown of 1 dependency."),
                    licenses.stdout());
        }
    }

    @Test
    void evaluatedCountsTheCoordinatesTheReportActuallyLists() throws IOException {
        try (CliTestRepository repository = CliTestRepository.start()) {
            addArtifacts(repository);
            // The members share a coordinate but not a graph, so the aggregate SBOM can carry more
            // component entries than the report lists rows.
            Path workspace = writeWorkspace(
                    repository,
                    """

                    [dependencies]
                    "org.example:gpl-lib" = "1.0.0"
                    "org.example:mit-lib" = "1.0.0"

                    [dependencyPolicy.licenses]
                    deny = ["GPL-3.0-only"]
                    """,
                    """

                    [dependencies]
                    "org.example:mit-lib" = "1.0.0"
                    "org.example:apache-lib" = "1.0.0"

                    [dependencyPolicy.licenses]
                    deny = ["GPL-3.0-only"]
                    """);
            Path cache = tempDir.resolve("cache");
            assertEquals(0, resolve(workspace, cache).exitCode());

            CommandResult licenses = execute("licenses", "--workspace", "--format", "json",
                    "--cwd", workspace.toString(), "--cache-root", cache.toString());

            assertEquals(0, licenses.exitCode(), licenses.stdout() + licenses.stderr());
            Set<String> reported = new LinkedHashSet<>();
            Matcher coordinates = COORDINATE.matcher(licenses.stdout());
            while (coordinates.find()) {
                reported.add(coordinates.group(1));
            }
            Matcher evaluated = EVALUATED.matcher(licenses.stdout());
            assertTrue(evaluated.find(), licenses.stdout());
            assertEquals(3, reported.size(), licenses.stdout());
            assertEquals(reported.size(), Integer.parseInt(evaluated.group(1)), licenses.stdout());
        }
    }

    private static CommandResult resolve(Path workspace, Path cache) {
        CommandResult resolve = execute("resolve", "--workspace",
                "--cwd", workspace.toString(), "--cache-root", cache.toString());
        assertEquals(0, resolve.exitCode(), resolve.stdout() + resolve.stderr());
        return resolve;
    }

    private Path writeWorkspace(CliTestRepository repository, String coreBody, String adminBody)
            throws IOException {
        Path workspace = tempDir.resolve("license-scope-workspace");
        Path core = workspace.resolve("modules/core");
        Path admin = workspace.resolve("apps/admin");
        Files.createDirectories(core);
        Files.createDirectories(admin);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "license-scope-workspace"
                members = ["modules/core", "apps/admin"]

                [repositories]
                test = "%s"
                """.formatted(repository.baseUri()));
        Files.writeString(core.resolve("zolt.toml"), memberConfig("core") + coreBody);
        Files.writeString(admin.resolve("zolt.toml"), memberConfig("admin") + adminBody);
        return workspace;
    }

    private static void addArtifacts(CliTestRepository repository) {
        repository.addArtifact("org.example", "gpl-lib", "1.0.0", pom("gpl-lib", "GPL-3.0-only"));
        repository.addArtifact("org.example", "mit-lib", "1.0.0", pom("mit-lib", "MIT"));
        repository.addArtifact("org.example", "apache-lib", "1.0.0", pom("apache-lib", "Apache-2.0"));
    }

    private static String pom(String artifact, String license) {
        return """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>%s</artifactId>
                  <version>1.0.0</version>
                  <licenses><license><name>%s</name></license></licenses>
                </project>
                """.formatted(artifact, license);
    }
}
