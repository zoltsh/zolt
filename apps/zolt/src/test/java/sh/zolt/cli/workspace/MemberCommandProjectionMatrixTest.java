package sh.zolt.cli.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import sh.zolt.cli.CliTestSupport.CommandResult;

/**
 * The report, publish, and framework-plan half of the member-command matrix: a member-local
 * {@code zolt.lock} is completely observationally irrelevant to every command that reads or plans from
 * a lock.
 *
 * <p>{@code MemberDirectoryCommandRoutingTest} pins the same invariant for the build-shaped commands
 * (build, run, test, package, native, classpath). This one covers the commands that PRODUCE evidence
 * or publications, where a wrong answer is not a failed build but a plausible document: an SBOM, a
 * license report, a quality verdict, an IDE model, a POM, an augmentation plan.
 *
 * <p>The planted lock is the dangerous shape, not an obviously broken one: current schema, valid, and
 * fingerprint-matching for the member's own config, so a standalone freshness gate accepts it without
 * complaint. It names one package no member depends on. Two properties are asserted per command: the
 * output is identical to the same command against a clean fixture of the same workspace, and the
 * planted file is left exactly as it was found — neither consumed nor rewritten.
 *
 * <p><strong>Extending this.</strong> Add an {@link Arguments} row naming the command and the
 * {@link Fixtures} kind its workspace needs. A command belongs here once it can be started in a member
 * directory and its answer depends on locked dependency facts. Two workspace kinds exist because the
 * shapes are mutually exclusive: a publishable jar member and a Quarkus fast-jar member cannot be the
 * same project.
 */
final class MemberCommandProjectionMatrixTest {
    /** A coordinate no member depends on, so any trace of it in an output is a read of the poison. */
    private static final String POISON = "poison";

    @TempDir
    private Path tempDir;

    /** Which workspace a row needs. */
    enum Fixtures {
        /** Three jar members, two of them publishable, with a sibling-only external. */
        REPORTS,
        /** A Quarkus-enabled member with a runtime extension, its deployment artifact, and a sibling. */
        QUARKUS
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("memberCommands")
    void plantedMemberLockChangesNothing(String name, Fixtures kind, List<String> arguments)
            throws IOException {
        try (Scenario clean = open(kind); Scenario poisoned = open(kind)) {
            CommandResult expected = clean.run(arguments);
            String planted = poisoned.plantPoisonedMemberLock();

            CommandResult actual = poisoned.run(arguments);

            assertEquals(expected.exitCode(), actual.exitCode(), actual.stdout() + actual.stderr());
            assertFalse(actual.stdout().contains(POISON), actual.stdout());
            assertFalse(actual.stderr().contains(POISON), actual.stderr());
            assertEquals(
                    clean.normalize(expected.stdout()),
                    poisoned.normalize(actual.stdout()),
                    () -> name + " read the member-local lock");
            assertGeneratedArtifactsMatch(name, clean, poisoned);
            assertEquals(
                    planted,
                    Files.readString(poisoned.memberLock()),
                    () -> name + " consumed or rewrote the member-local lock");
        }
    }

    /**
     * The exit code is deliberately not asserted: {@code publish --dry-run --central} reports an
     * unsatisfied Central checklist as a non-zero exit for this fixture (no signing key), which is the
     * right answer and not what this test is about. What must hold for every row is that the command
     * answered from the root lock and left no member-local lock behind.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("memberCommands")
    void memberCommandNeverCreatesAMemberLocalLock(String name, Fixtures kind, List<String> arguments)
            throws IOException {
        try (Scenario scenario = open(kind)) {
            CommandResult result = scenario.run(arguments);

            assertFalse(result.stdout().isBlank() && result.stderr().isBlank(),
                    () -> name + " produced no output at all");
            assertFalse(
                    Files.exists(scenario.memberLock()),
                    () -> name + " created a member-local lock at " + scenario.memberLock());
            assertTrue(Files.exists(scenario.rootLock()));
        }
    }

    /**
     * Every member-facing command whose answer depends on locked dependency facts. The publish rows
     * cover both target shapes: a plain repository (which requires a configured repository) and Maven
     * Central (which does not, and assembles a bundle instead).
     */
    private static Stream<Arguments> memberCommands() {
        return Stream.of(
                Arguments.of("ide model", Fixtures.REPORTS, List.of("ide", "model", "--format", "json")),
                Arguments.of("check", Fixtures.REPORTS, List.of("check", "--offline", "--check", "lockfile",
                        "--check", "dependency-policy", "--check", "license-policy")),
                Arguments.of("sbom", Fixtures.REPORTS, List.of("sbom", "--offline", "--format", "cyclonedx")),
                Arguments.of("licenses", Fixtures.REPORTS, List.of("licenses", "--offline")),
                Arguments.of("publish --dry-run", Fixtures.REPORTS, List.of("publish", "--dry-run")),
                Arguments.of("publish --dry-run --central", Fixtures.REPORTS,
                        List.of("publish", "--dry-run", "--central")),
                Arguments.of("publish --dry-run --sbom", Fixtures.REPORTS,
                        List.of("publish", "--dry-run", "--sbom")),
                Arguments.of("quarkus plan", Fixtures.QUARKUS, List.of("quarkus", "plan")));
    }

    private Scenario open(Fixtures kind) throws IOException {
        return kind == Fixtures.QUARKUS
                ? new QuarkusScenario(MemberQuarkusFixture.create(tempDir))
                : new ReportsScenario(MemberProjectionFixture.createPublishable(tempDir));
    }

    /** One workspace a row can run against, with the two facts the matrix asserts over. */
    private interface Scenario extends AutoCloseable {
        CommandResult run(List<String> arguments);

        String plantPoisonedMemberLock() throws IOException;

        Path memberLock();

        Path rootLock();

        /** Where the member's lock-derived publication artifacts are written. */
        Path generatedRoot();

        /**
         * Two fixtures live in different temp directories and were packaged at different moments, so
         * absolute paths and freshly built jar bytes differ by construction. Only those are erased;
         * everything the lock decides — the generated POM, the attached SBOM, every classpath entry,
         * every dependency listing, and their digests — must match exactly.
         */
        String normalize(String output);

        @Override
        void close();
    }

    private record ReportsScenario(MemberProjectionFixture fixture) implements Scenario {
        @Override
        public CommandResult run(List<String> arguments) {
            if (arguments.getFirst().equals("publish")) {
                CommandResult packaged = fixture.api(
                        "package", "--workspace", "--member", MemberProjectionFixture.API_MEMBER);
                assertEquals(0, packaged.exitCode(), packaged.stdout() + packaged.stderr());
            }
            return fixture.api(arguments.toArray(String[]::new));
        }

        @Override
        public String plantPoisonedMemberLock() throws IOException {
            return fixture.plantPoisonedMemberLock();
        }

        @Override
        public Path memberLock() {
            return fixture.memberLock();
        }

        @Override
        public Path rootLock() {
            return fixture.rootLock();
        }

        @Override
        public Path generatedRoot() {
            return fixture.apiDir().resolve("target/publish");
        }

        @Override
        public String normalize(String output) {
            return maskDigests(output
                    .replace(absolute(fixture.workspaceDir()), "<workspace>")
                    .replace(absolute(fixture.cacheRoot()), "<cache>")
                    .replace(fixture.repository().baseUri().toString(), "<repository>"));
        }

        @Override
        public void close() {
            fixture.close();
        }
    }

    private record QuarkusScenario(MemberQuarkusFixture fixture) implements Scenario {
        @Override
        public CommandResult run(List<String> arguments) {
            List<String> command = new ArrayList<>(arguments);
            command.add("--cwd");
            command.add(fixture.quarkusMember().toString());
            command.add("--cache-root");
            command.add(fixture.cacheRoot().toString());
            return execute(command.toArray(String[]::new));
        }

        @Override
        public String plantPoisonedMemberLock() throws IOException {
            return fixture.plantPoisonedMemberLock();
        }

        @Override
        public Path memberLock() {
            return fixture.memberLock();
        }

        @Override
        public Path rootLock() {
            return fixture.rootLock();
        }

        @Override
        public Path generatedRoot() {
            return fixture.quarkusMember().resolve("target/publish");
        }

        @Override
        public String normalize(String output) {
            return maskDigests(output
                    .replace(absolute(fixture.workspaceDir()), "<workspace>")
                    .replace(absolute(fixture.cacheRoot()), "<cache>"));
        }

        @Override
        public void close() {
        }
    }

    private static String absolute(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /**
     * Masks every hex digest. A jar built by {@code zolt package} embeds timestamps, so two fixtures
     * packaged seconds apart differ there for reasons that have nothing to do with which lock was read.
     * The digests that DO answer that question are not dropped — {@link #assertGeneratedArtifactsMatch}
     * compares the generated POM and the attached SBOM byte for byte instead, which is the stronger
     * statement: those files ARE the projection, rendered.
     */
    private static String maskDigests(String output) {
        return output.replaceAll("\\b[0-9a-f]{32}\\b|\\b[0-9a-f]{40}\\b|\\b[0-9a-f]{64}\\b", "<digest>");
    }

    /**
     * Every file the command generated FROM the lock — the POM, and the CycloneDX SBOM when attached —
     * compared byte for byte. A member-local lock that was read would change exactly these.
     */
    private static void assertGeneratedArtifactsMatch(String name, Scenario clean, Scenario poisoned)
            throws IOException {
        Map<String, String> expected = generated(clean.generatedRoot());
        Map<String, String> actual = generated(poisoned.generatedRoot());
        assertEquals(expected.keySet(), actual.keySet(), () -> name + " generated a different file set");
        assertEquals(expected, actual, () -> name + " generated different bytes from the lock");
    }

    private static Map<String, String> generated(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return Map.of();
        }
        Map<String, String> contents = new TreeMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".pom") || fileName.endsWith(".json")) {
                    contents.put(fileName, Files.readString(file));
                }
            }
        }
        return contents;
    }
}
