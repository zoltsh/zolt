package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import sh.zolt.manifest.CoveragePercentage;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.JavaBinaryClassName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.TestClassPattern;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredCoverage;
import sh.zolt.manifest.authored.AuthoredTestRuntime;
import sh.zolt.manifest.authored.AuthoredTestSuite;
import sh.zolt.manifest.authored.AuthoredTests;

final class ManifestTestsCoverageWriterTest {
    @Test
    void emitsTheCompleteModelInExactSchemaAndModelOrder() {
        AuthoredTests tests = new AuthoredTests(
                Optional.of(new AuthoredTests.Sources(
                        List.of(path("src/test/java"), path("src/custom-test/java")),
                        List.of(path("src/spec/groovy")))),
                Optional.of(new AuthoredTestRuntime(
                        List.of("-Xmx2g", "-Dfile.encoding=UTF-8"),
                        Map.of("zeta", "last", "alpha.key", "first"),
                        Map.of(environment("Z_ENV"), "last", environment("A_ENV"), "first"),
                        List.of(
                                AuthoredTestRuntime.Event.FAILED,
                                AuthoredTestRuntime.Event.PASSED,
                                AuthoredTestRuntime.Event.SKIPPED))),
                Optional.of(new AuthoredTests.Integration(
                        List.of(path("src/integration-test/java"), path("src/contract/java")),
                        List.of(path("fixtures/integration")))),
                Map.of(
                        id("zeta"),
                        suite(
                                List.of(pattern("*ZetaTest")),
                                List.of(),
                                List.of("slow"),
                                List.of(),
                                Optional.empty(),
                                List.of()),
                        id("alpha"),
                        suite(
                                List.of(pattern("*ZetaTest"), pattern("*AlphaTest")),
                                List.of(pattern("*FlakyTest")),
                                List.of("zeta", "alpha"),
                                List.of("slow", "flaky"),
                                Optional.of(4),
                                List.of(
                                        lock("com.example.ZetaTest", "network"),
                                        lock(
                                                "com.example.AlphaTest",
                                                "redis",
                                                "database")))));
        AuthoredCoverage coverage = coverage(88.0, 74.5, 80.25, 100.0);

        String output = write(Optional.of(tests), Optional.of(coverage));

        assertEquals(
                """
                [test.sources]
                java = ["src/custom-test/java", "src/test/java"]
                groovy = ["src/spec/groovy"]

                [test.runtime]
                jvmArgs = ["-Xmx2g", "-Dfile.encoding=UTF-8"]
                properties = { "alpha.key" = "first", "zeta" = "last" }
                env = { "A_ENV" = "first", "Z_ENV" = "last" }
                events = ["passed", "skipped", "failed"]

                [test.integration]
                sources = ["src/contract/java", "src/integration-test/java"]
                resources = ["fixtures/integration"]

                [test.suites.alpha]
                classes = ["*ZetaTest", "*AlphaTest"]
                excludeClasses = ["*FlakyTest"]
                tags = ["zeta", "alpha"]
                excludeTags = ["slow", "flaky"]
                workers = 4
                locks = [
                    { class = "com.example.AlphaTest", resources = ["database", "redis"] },
                    { class = "com.example.ZetaTest", resources = ["network"] },
                ]

                [test.suites.zeta]
                classes = ["*ZetaTest"]
                tags = ["slow"]

                [coverage]
                line = 88
                branch = 74.5
                instruction = 80.25
                method = 100
                """,
                output);
        assertValid(output);

        var decoded = decode(output);
        assertEquals(tests, decoded.tests().orElseThrow());
        assertEquals(coverage, decoded.coverage().orElseThrow());
    }

    @Test
    void omitsConventionalRootsEmptyCollectionsAndContextualDefaults() {
        AuthoredTests conventional = new AuthoredTests(
                Optional.of(new AuthoredTests.Sources(
                        List.of(path("src/test/java")), List.of())),
                Optional.empty(),
                Optional.of(new AuthoredTests.Integration(
                        List.of(path("src/integration-test/java")),
                        List.of(path("src/integration-test/resources")))),
                Map.of());
        assertEquals("", write(Optional.of(conventional), Optional.empty()));
        assertEquals(
                "",
                write(Optional.of(AuthoredTests.empty()), Optional.empty()));
        assertEquals("", write(Optional.empty(), Optional.empty()));

        AuthoredTests withSuite = new AuthoredTests(
                conventional.sources(),
                Optional.empty(),
                conventional.integration(),
                Map.of(id("fast"), suite(
                        List.of(),
                        List.of(),
                        List.of("fast"),
                        List.of(),
                        Optional.of(1),
                        List.of())));

        String output = write(Optional.of(withSuite), Optional.empty());

        assertEquals(
                """
                [test.suites.fast]
                tags = ["fast"]
                """,
                output);
        AuthoredTests normalized = decode(output).tests().orElseThrow();
        assertEquals(Optional.empty(), normalized.sources());
        assertEquals(Optional.empty(), normalized.integration());
        assertEquals(Optional.empty(), normalized.suites().get(id("fast")).workers());
        assertValid(output);
    }

    @Test
    void sortsNamedSuitesAndResourceLocksWithoutDroppingADefaultOnlySuite() {
        AuthoredTests tests = new AuthoredTests(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(
                        id("solo"),
                        suite(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                Optional.of(1),
                                List.of()),
                        id("locked"),
                        suite(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                Optional.empty(),
                                List.of(
                                        lock("com.example.ZetaTest", "zeta", "alpha"),
                                        lock("com.example.AlphaTest", "database")))));

        String output = write(Optional.of(tests), Optional.empty());

        assertEquals(
                """
                [test.suites.locked]
                locks = [
                    { class = "com.example.AlphaTest", resources = ["database"] },
                    { class = "com.example.ZetaTest", resources = ["alpha", "zeta"] },
                ]

                [test.suites.solo]
                workers = 1
                """,
                output);
        assertFalse(output.contains("{ }"));
        assertFalse(output.contains("resources = [\n"));
        assertEquals(tests, decode(output).tests().orElseThrow());
        assertValid(output);
    }

    @Test
    void canonicalizesWholeFractionalAndNegativeZeroCoverageFloors() {
        AuthoredCoverage coverage = coverage(100.0, 74.5000, -0.0, 88.125);

        String output = write(Optional.empty(), Optional.of(coverage));

        assertEquals(
                """
                [coverage]
                line = 100
                branch = 74.5
                instruction = 0
                method = 88.125
                """,
                output);
        assertEquals(coverage, decode(output).coverage().orElseThrow());
        assertValid(output);
    }

    private static String write(
            Optional<AuthoredTests> tests, Optional<AuthoredCoverage> coverage) {
        ManifestTomlEmitter emitter = new ManifestTomlEmitter();
        new ManifestTestsCoverageWriter().write(emitter, tests, coverage);
        return emitter.finish();
    }

    private static AuthoredBuildConfiguration decode(String output) {
        return decodeAuthoredManifest(
                        "[project]\nname = \"round-trip\"\n\n" + output)
                .build();
    }

    private static void assertValid(String output) {
        assertFalse(Toml.parse(output).hasErrors());
    }

    private static AuthoredTestSuite suite(
            List<TestClassPattern> classes,
            List<TestClassPattern> excludeClasses,
            List<String> tags,
            List<String> excludeTags,
            Optional<Integer> workers,
            List<AuthoredTestSuite.Lock> locks) {
        return new AuthoredTestSuite(
                classes, excludeClasses, tags, excludeTags, workers, locks);
    }

    private static AuthoredTestSuite.Lock lock(
            String className, String... resources) {
        return new AuthoredTestSuite.Lock(
                new JavaBinaryClassName(className),
                Arrays.stream(resources)
                        .map(ManifestTestsCoverageWriterTest::id)
                        .toList());
    }

    private static AuthoredCoverage coverage(
            double line, double branch, double instruction, double method) {
        return new AuthoredCoverage(
                Optional.of(new CoveragePercentage(line)),
                Optional.of(new CoveragePercentage(branch)),
                Optional.of(new CoveragePercentage(instruction)),
                Optional.of(new CoveragePercentage(method)));
    }

    private static TestClassPattern pattern(String value) {
        return new TestClassPattern(value);
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }

    private static LocalId id(String value) {
        return new LocalId(value);
    }

    private static EnvironmentVariableName environment(String value) {
        return new EnvironmentVariableName(value);
    }
}
