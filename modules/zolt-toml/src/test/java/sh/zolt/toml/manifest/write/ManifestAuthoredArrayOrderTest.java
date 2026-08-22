package sh.zolt.toml.manifest.write;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestSemanticTestSupport.decodeAuthoredManifest;

import java.util.List;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredBuild;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;

/**
 * Design §5.5: order-bearing authored arrays keep their authored order end to end. The authored model
 * preserves order and rejects duplicates, effective semantics decide whether order matters, and the
 * canonical writer sorts only truly set-like collections.
 */
final class ManifestAuthoredArrayOrderTest {
    private static final String ORDERED = """
            [project]
            name = "demo"
            version = "1.0.0"
            group = "com.example"
            java = 21

            [build]
            sources = ["src/zeta/java", "src/alpha/java"]

            [resources]
            main = ["src/zeta/resources", "src/alpha/resources"]
            test = ["src/zeta/test-resources", "src/alpha/test-resources"]

            [test.sources]
            java = ["src/zeta/test-java", "src/alpha/test-java"]

            [test.integration]
            sources = ["src/zeta/it-java", "src/alpha/it-java"]
            resources = ["src/zeta/it-resources", "src/alpha/it-resources"]
            """;

    @Test
    void authoredModelKeepsOrderBearingArraysInAuthoredOrder() {
        AuthoredManifest authored = decodeAuthoredManifest(ORDERED);

        assertEquals(
                List.of("src/zeta/java", "src/alpha/java"),
                authored.build().build().map(AuthoredBuild::sources).orElseThrow().stream()
                        .map(Object::toString)
                        .toList());
        AuthoredResources resources = authored.build().resources().orElseThrow();
        assertEquals(
                List.of("src/zeta/resources", "src/alpha/resources"),
                resources.main().stream().map(Object::toString).toList());
        assertEquals(
                List.of("src/zeta/test-resources", "src/alpha/test-resources"),
                resources.test().stream().map(Object::toString).toList());
        AuthoredTests tests = authored.build().tests().orElseThrow();
        assertEquals(
                List.of("src/zeta/test-java", "src/alpha/test-java"),
                tests.sources().orElseThrow().java().stream().map(Object::toString).toList());
        AuthoredTests.Integration integration = tests.integration().orElseThrow();
        assertEquals(
                List.of("src/zeta/it-java", "src/alpha/it-java"),
                integration.sources().stream().map(Object::toString).toList());
        assertEquals(
                List.of("src/zeta/it-resources", "src/alpha/it-resources"),
                integration.resources().stream().map(Object::toString).toList());
    }

    /** The adapter projects the first authored root onto the legacy single primary root. */
    @Test
    void firstAuthoredRootStaysThePrimaryRoot() {
        ProjectConfig config = new ManifestProjectConfigLoader().load(ORDERED);

        assertEquals("src/zeta/java", config.build().source());
        assertEquals(
                List.of("src/zeta/java", "src/alpha/java"), config.build().sourceRoots());
        assertEquals("src/zeta/test-java", config.build().test());
        assertEquals(
                List.of("src/zeta/test-java", "src/alpha/test-java"),
                config.build().testSources());
        assertEquals(
                List.of("src/zeta/resources", "src/alpha/resources"),
                config.build().resourceRoots());
        assertEquals(
                List.of("src/zeta/test-resources", "src/alpha/test-resources"),
                config.build().testResourceRoots());
        assertEquals(
                List.of("src/zeta/it-java", "src/alpha/it-java"),
                config.build().integrationTestSources());
        assertEquals(
                List.of("src/zeta/it-resources", "src/alpha/it-resources"),
                config.build().integrationTestResourceRoots());
    }

    @Test
    void canonicalWriteRoundTripsAuthoredOrderForOrderBearingArrays() {
        String canonical = new ManifestCanonicalWriter().write(decodeAuthoredManifest(ORDERED));

        assertTrue(
                canonical.contains("sources = [\"src/zeta/java\", \"src/alpha/java\"]"),
                canonical);
        assertTrue(
                canonical.contains("main = [\"src/zeta/resources\", \"src/alpha/resources\"]"),
                canonical);
        assertTrue(
                canonical.contains("java = [\"src/zeta/test-java\", \"src/alpha/test-java\"]"),
                canonical);
        assertEquals(canonical, new ManifestCanonicalWriter().write(decodeAuthoredManifest(canonical)));
    }

    /** Set-like collections stay sorted in the authored model and therefore in canonical output. */
    @Test
    void setLikeCollectionsStaySortedInCanonicalOutput() {
        String canonical = new ManifestCanonicalWriter().write(decodeAuthoredManifest("""
                [project]
                name = "demo"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [dependencies.policy.licenses]
                allow = ["MIT", "Apache-2.0"]
                """));

        assertTrue(canonical.contains("allow = [\"Apache-2.0\", \"MIT\"]"), canonical);
    }

    @Test
    void duplicateOrderBearingEntriesAreStillRejected() {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeAuthoredManifest("""
                        [project]
                        name = "demo"
                        version = "1.0.0"
                        group = "com.example"
                        java = 21

                        [build]
                        sources = ["src/alpha/java", "src/alpha/java"]
                        """));

        assertTrue(failure.getMessage().contains("src/alpha/java"), failure.getMessage());
    }
}
