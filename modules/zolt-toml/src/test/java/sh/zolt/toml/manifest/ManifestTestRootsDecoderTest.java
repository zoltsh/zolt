package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredTests;
import sh.zolt.toml.ZoltConfigException;

final class ManifestTestRootsDecoderTest {
    private final ManifestTestRootsDecoder decoder = new ManifestTestRootsDecoder();

    @Test
    void preservesOmissionWithoutApplyingConventionalDefaults() {
        ManifestDecodeIndex absent = ManifestSemanticTestSupport.index("");
        assertTrue(decoder.decodeSources(absent).isEmpty());
        assertTrue(decoder.decodeIntegration(absent).isEmpty());
    }

    @Test
    void decodesAllFourArraysAsSortedImmutableRoots() {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index("""
                [test.sources]
                java = ["src/z-test/java", "src/a-test/java"]
                groovy = ["src/z-test/groovy", "src/a-test/groovy"]

                [test.integration]
                sources = ["src/z-integration/java", "src/a-integration/java"]
                resources = ["src/z-integration/resources", "src/a-integration/resources"]
                """);

        AuthoredTests.Sources sources = decoder.decodeSources(index).orElseThrow();
        AuthoredTests.Integration integration = decoder.decodeIntegration(index).orElseThrow();
        assertEquals(
                List.of(path("src/a-test/java"), path("src/z-test/java")),
                sources.java());
        assertEquals(
                List.of(path("src/a-test/groovy"), path("src/z-test/groovy")),
                sources.groovy());
        assertEquals(
                List.of(path("src/a-integration/java"), path("src/z-integration/java")),
                integration.sources());
        assertEquals(
                List.of(
                        path("src/a-integration/resources"),
                        path("src/z-integration/resources")),
                integration.resources());
        assertThrows(UnsupportedOperationException.class, sources.java()::clear);
        assertThrows(UnsupportedOperationException.class, integration.resources()::clear);
    }

    @Test
    void acceptsEachSingleAuthoredSide() {
        assertEquals(
                List.of(path("custom/java")),
                sources("java = [\"custom/java\"]\n").orElseThrow().java());
        assertEquals(
                List.of(path("custom/groovy")),
                sources("groovy = [\"custom/groovy\"]\n").orElseThrow().groovy());
        assertEquals(
                List.of(path("custom/integration")),
                integration("sources = [\"custom/integration\"]\n")
                        .orElseThrow()
                        .sources());
        assertEquals(
                List.of(path("custom/resources")),
                integration("resources = [\"custom/resources\"]\n")
                        .orElseThrow()
                        .resources());
    }

    @Test
    void delaysEmptyAggregateFailureUntilBothSiblingFieldsAreKnown() {
        assertSourcesFailure("java = []\n", "`test.sources.java`");
        assertSourcesFailure("groovy = []\n", "`test.sources.groovy`");
        assertSourcesFailure(
                "java = []\ngroovy = []\n", "`test.sources.java`");
        assertIntegrationFailure("sources = []\n", "`test.integration.sources`");
        assertIntegrationFailure("resources = []\n", "`test.integration.resources`");
        assertIntegrationFailure(
                "sources = []\nresources = []\n", "`test.integration.sources`");

        AuthoredTests.Sources sources = sources(
                "java = []\ngroovy = [\"custom/groovy\"]\n").orElseThrow();
        assertTrue(sources.java().isEmpty());
        assertEquals(List.of(path("custom/groovy")), sources.groovy());
        AuthoredTests.Integration integration = integration(
                "sources = []\nresources = [\"custom/resources\"]\n").orElseThrow();
        assertTrue(integration.sources().isEmpty());
        assertEquals(List.of(path("custom/resources")), integration.resources());
    }

    @Test
    void anchorsEveryLaterDuplicateToItsExactArrayItem() {
        assertSourcesFailure(
                "java = [\"custom/java\", \"custom/java\"]\n",
                "`test.sources.java[1]`");
        assertSourcesFailure(
                "groovy = [\"custom/groovy\", \"custom/groovy\"]\n",
                "`test.sources.groovy[1]`");
        assertIntegrationFailure(
                "sources = [\"custom/java\", \"custom/java\"]\n",
                "`test.integration.sources[1]`");
        assertIntegrationFailure(
                "resources = [\"custom/resources\", \"custom/resources\"]\n",
                "`test.integration.resources[1]`");
    }

    @Test
    void leavesInvalidRelativePathsAtTheSchemaOwnedFieldPath() {
        ZoltConfigException sources = assertThrows(
                ZoltConfigException.class,
                () -> sources("java = [\"../outside\"]\n"));
        assertTrue(sources.getMessage().contains("`test.sources.java`"), sources.getMessage());
        assertFalse(sources.getMessage().contains("java[0]"), sources.getMessage());

        ZoltConfigException integration = assertThrows(
                ZoltConfigException.class,
                () -> integration("resources = [\"/outside\"]\n"));
        assertTrue(
                integration.getMessage().contains("`test.integration.resources`"),
                integration.getMessage());
        assertFalse(
                integration.getMessage().contains("resources[0]"),
                integration.getMessage());
    }

    private Optional<AuthoredTests.Sources> sources(String fields) {
        return decoder.decodeSources(ManifestSemanticTestSupport.index(
                "[test.sources]\n" + fields));
    }

    private Optional<AuthoredTests.Integration> integration(String fields) {
        return decoder.decodeIntegration(ManifestSemanticTestSupport.index(
                "[test.integration]\n" + fields));
    }

    private void assertSourcesFailure(String fields, String path) {
        assertFailure(() -> sources(fields), path);
    }

    private void assertIntegrationFailure(String fields, String path) {
        assertFailure(() -> integration(fields), path);
    }

    private static void assertFailure(Runnable decode, String path) {
        ZoltConfigException failure = assertThrows(ZoltConfigException.class, decode::run);
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }
}
