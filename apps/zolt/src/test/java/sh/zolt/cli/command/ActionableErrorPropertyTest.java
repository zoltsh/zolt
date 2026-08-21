package sh.zolt.cli.command;

import static sh.zolt.cli.CliTestSupport.execute;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport;
import sh.zolt.cli.CliTestSupport.CommandResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Output-level enforcement of the actionable-error invariant: every covered user-facing failure must
 * be driven end-to-end through the real CLI and render a non-empty {@code Next:} remediation line on
 * stderr. This is the behavioral half of the contract (the static half lives in
 * {@code sh.zolt.arch.ActionableErrorContractTest}); a covered command that stops emitting a
 * remediation line — for example a new flat-string throw in resolve/build/test/run/package — fails
 * this test instead of silently slipping through {@code CommandErrorBlock}'s extraction heuristic.
 *
 * <p>Scenarios are representative of the high-traffic error families, not exhaustive. Paths that
 * legitimately cannot carry a Zolt remediation line (picocli's own usage errors) are captured in
 * {@link #frameworkUsageErrorsAreAllowlisted()} as an explicit documented exception so the gap is
 * visible debt rather than silent sampling.
 */
final class ActionableErrorPropertyTest {
    @TempDir
    private Path tempDir;

    /**
     * Representative user-facing failures across the migrated families. Each scenario writes its own
     * fixture under {@code tempDir} and returns the CLI arguments to invoke. The property asserts the
     * rendered stderr block carries a non-empty {@code Next:} line for every one of them.
     */
    private static List<Scenario> coveredScenarios() {
        return List.of(
                // resolve / config families ---------------------------------------------------
                scenario("resolve: missing zolt.toml", (dir, repo) ->
                        List.of("resolve", "--cwd", dir.toString(), "--cache-root", cache(dir))),
                scenario("resolve: unknown top-level section", (dir, repo) -> {
                    writeConfig(dir, "unknown-section", "\n[bogusSection]\n");
                    return List.of("resolve", "--cwd", dir.toString(), "--cache-root", cache(dir));
                }),
                scenario("resolve: unsupported framework shape ([kotlin])", (dir, repo) -> {
                    writeConfig(dir, "unsupported-kotlin", "\n[kotlin]\n");
                    return List.of("resolve", "--cwd", dir.toString(), "--cache-root", cache(dir));
                }),
                scenario("build --workspace: not a workspace directory", (dir, repo) -> {
                    writeConfig(dir, "not-a-workspace", "");
                    return List.of(
                            "build", "--workspace", "--cwd", dir.toString(), "--cache-root", cache(dir));
                }),
                repositoryScenario("resolve: unresolved/unknown dependency (404)", (dir, repo) -> {
                    writeConfig(dir, "unknown-dep", """

                            [repositories]
                            central = false

                            [repositories.test]
                            url = "%s"

                            [dependencies]
                            "com.example:does-not-exist" = "9.9.9"
                            """.formatted(repo.orElseThrow().baseUri()));
                    return List.of("resolve", "--cwd", dir.toString(), "--cache-root", cache(dir));
                }),
                scenario("resolve: unsupported external dependency version", (dir, repo) -> {
                    writeConfig(dir, "bad-version", """

                            [dependencies]
                            "com.example:thing" = "1.0.+"
                            """);
                    return List.of("resolve", "--cwd", dir.toString(), "--cache-root", cache(dir));
                }),
                scenario("resolve --locked: missing lockfile", (dir, repo) -> {
                    writeConfig(dir, "locked-missing", "");
                    return List.of("resolve", "--locked", "--cwd", dir.toString(), "--cache-root", cache(dir));
                }),
                scenario("resolve: unsupported repository overlay value", (dir, repo) -> {
                    writeConfig(dir, "bad-overlay", "");
                    return List.of(
                            "resolve",
                            "--repository-overlay", "not-a-real-overlay",
                            "--cwd", dir.toString(),
                            "--cache-root", cache(dir));
                }),

                // build family ----------------------------------------------------------------
                scenario("build: compile error", (dir, repo) -> {
                    writeConfig(dir, "compile-error", "");
                    writeSource(dir, """
                            package com.example;

                            public final class Main {
                                missing
                            }
                            """);
                    return List.of("build", "--cwd", dir.toString(), "--cache-root", cache(dir));
                }),
                scenario("build: malformed/unreadable lockfile", (dir, repo) -> {
                    writeConfig(dir, "bad-lockfile", "");
                    // A lockfile that looks generated (contains the Sha256 marker) but is not valid TOML
                    // makes the locked re-resolve fail while parsing it.
                    try {
                        Files.writeString(dir.resolve("zolt.lock"), "Sha256 = \nthis is = = not valid [[[ toml\n");
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                    return List.of("build", "--cwd", dir.toString(), "--cache-root", cache(dir));
                }),

                // package family --------------------------------------------------------------
                scenario("package: --format without --plan", (dir, repo) -> {
                    writeConfig(dir, "pkg-format", "");
                    return List.of(
                            "package",
                            "--format", "json",
                            "--cwd", dir.toString(),
                            "--cache-root", cache(dir));
                }),

                // test family -----------------------------------------------------------------
                scenario("test: missing zolt.toml", (dir, repo) ->
                        List.of("test", "--cwd", dir.toString(), "--cache-root", cache(dir))),

                // run family ------------------------------------------------------------------
                scenario("run: missing zolt.toml", (dir, repo) ->
                        List.of("run", "--cwd", dir.toString(), "--cache-root", cache(dir))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coveredScenarios")
    void everyCoveredFailureRendersANonEmptyNextLine(Scenario scenario) throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(slug(scenario.name())));
        if (!scenario.requiresRepository()) {
            assertScenarioRendersNextLine(scenario, dir, Optional.empty());
            return;
        }
        try (CliTestRepository repository = CliTestRepository.start()) {
            assertScenarioRendersNextLine(scenario, dir, Optional.of(repository));
        }
    }

    private static void assertScenarioRendersNextLine(
            Scenario scenario,
            Path dir,
            Optional<CliTestRepository> repository) throws IOException {
        List<String> args = scenario.setup().run(dir, repository);
        CommandResult result = execute(args.toArray(String[]::new));

        assertNotEquals(
                0,
                result.exitCode(),
                () -> scenario.name() + " was expected to fail but exited 0.\nstdout:\n" + result.stdout());
        assertTrue(
                nextRemediation(result.stderr()).isPresent(),
                () -> "Scenario '" + scenario.name()
                        + "' must render a non-empty 'Next:' remediation line, but stderr was:\n"
                        + result.stderr());
    }

    /**
     * Allowlisted gap: picocli's own usage/parameter errors (invalid option values,
     * unknown options) are rendered by the framework before any command runs, so they do not flow
     * through {@code CommandFailures} and carry no Zolt {@code Next:} line. They exit with picocli's
     * usage code (2) and print a synopsis instead. This is intentional, documented debt; the
     * assertion below pins the current behavior so a future change that routes usage errors through
     * the actionable path is noticed.
     */
    @Test
    void frameworkUsageErrorsAreAllowlisted() {
        CommandResult invalidColor = execute("--color", "rainbow", "help");

        assertNotEquals(0, invalidColor.exitCode());
        assertTrue(
                nextRemediation(invalidColor.stderr()).isEmpty(),
                () -> "Allowlisted picocli usage errors are not expected to carry a 'Next:' line yet; "
                        + "if this changed, update the allowlist. stderr:\n" + invalidColor.stderr());
    }

    private static Optional<String> nextRemediation(String stderr) {
        return stderr.lines()
                .filter(line -> line.startsWith("Next: "))
                .map(line -> line.substring("Next: ".length()).trim())
                .filter(remediation -> !remediation.isEmpty())
                .findFirst();
    }

    private static void writeConfig(Path dir, String name, String extra) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("zolt.toml"), CliTestSupport.memberConfig(name) + extra);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static void writeSource(Path dir, String content) {
        try {
            Path source = dir.resolve("src/main/java/com/example/Main.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, content);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String cache(Path dir) {
        return dir.resolve("zolt-cache").toString();
    }

    private static String slug(String name) {
        return name.replaceAll("[^a-zA-Z0-9]+", "-");
    }

    private static Scenario scenario(String name, ScenarioSetup setup) {
        return new Scenario(name, false, setup);
    }

    private static Scenario repositoryScenario(String name, ScenarioSetup setup) {
        return new Scenario(name, true, setup);
    }

    @FunctionalInterface
    private interface ScenarioSetup {
        List<String> run(Path dir, Optional<CliTestRepository> repository) throws IOException;
    }

    private record Scenario(String name, boolean requiresRepository, ScenarioSetup setup) {
        @Override
        public String toString() {
            return name;
        }
    }
}
