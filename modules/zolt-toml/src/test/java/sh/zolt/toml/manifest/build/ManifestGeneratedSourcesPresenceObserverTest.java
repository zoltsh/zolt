package sh.zolt.toml.manifest.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeGeneratedSources;
import static sh.zolt.toml.manifest.ManifestBuildTestSupport.decodeGeneratedSourcesWithNullIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.toml.ZoltConfigException;

final class ManifestGeneratedSourcesPresenceObserverTest {
    @Test
    void observesOnlyTheFirstCanonicalCollectionInReverseSourceOrder() {
        ArrayList<AuthoredGeneratedSources> observed = new ArrayList<>();
        String source = """
                [generated.test]
                [generated.main]
                [generated.presets]
                [generated.tools]
                """;

        AuthoredGeneratedSources generated =
                decodeGeneratedSources(source, observed::add).orElseThrow();

        assertEquals(List.of(AuthoredGeneratedSources.empty()), observed);
        assertEquals(AuthoredGeneratedSources.empty(), generated);
        AuthoredGeneratedSources first = observed.getFirst();
        assertThrows(UnsupportedOperationException.class, first.main()::clear);
        assertThrows(UnsupportedOperationException.class, first.test()::clear);
        assertThrows(UnsupportedOperationException.class, first.tools().declarations()::clear);
        assertThrows(UnsupportedOperationException.class, first.presets().openApi()::clear);
        assertObservedFailure(source, "[generated.tools]");
    }

    @Test
    void observesEveryExplicitAndInlineEmptyCollectionPresence() {
        for (String source : List.of(
                "[generated.tools]\n",
                "[generated.presets]\n",
                "[generated.main]\n",
                "[generated.test]\n",
                "generated = { tools = {} }\n",
                "generated = { presets = {} }\n",
                "generated = { main = {} }\n",
                "generated = { test = {} }\n")) {
            ArrayList<AuthoredGeneratedSources> observed = new ArrayList<>();

            AuthoredGeneratedSources generated =
                    decodeGeneratedSources(source, observed::add).orElseThrow();

            assertEquals(List.of(AuthoredGeneratedSources.empty()), observed, source);
            assertEquals(AuthoredGeneratedSources.empty(), generated, source);
        }
    }

    @Test
    void observesImplicitNamedCollectionsAtTheirCanonicalParentSections() {
        assertObservedFailure("""
                [generated.tools.invalid]
                kind = "process"
                """, "[generated.tools]");
        assertObservedFailure("""
                [generated.presets.invalid]
                kind = "openapi"
                generator = " "
                """, "[generated.presets]");
        assertObservedFailure("""
                [generated.main.invalid]
                kind = "openapi"
                """, "[generated.main]");
        assertObservedFailure("""
                [generated.test.invalid]
                kind = "openapi"
                """, "[generated.test]");
    }

    @Test
    void continuesCanonicalChildAndReferenceValidationAfterObservation() {
        assertObservedThenLeafFailure("""
                [generated.tools.process]
                kind = "process"
                binary = "npm"
                versionCommand = ["npm", "--version"]
                allowUnpinnedTool = false
                """, "`generated.tools.process.allowUnpinnedTool`");
        assertObservedThenLeafFailure("""
                [generated.presets.invalid]
                kind = "openapi"
                generator = " "
                """, "`generated.presets.invalid.generator`");
        assertObservedThenLeafFailure("""
                [generated.main.model]
                kind = "exec"
                tool = "missing-tool"
                inputs = ["schema.sql"]
                output = "target/generated/model"
                produces = "java-sources"
                """, "`generated.main.model.tool`");
        assertObservedThenLeafFailure("""
                [generated.test.protocol]
                kind = "protobuf"
                tool = "missing-tool"
                inputs = ["service.proto"]
                """, "`generated.test.protocol.tool`");
    }

    @Test
    void leavesWholeDocumentShapeFailuresUnobserved() {
        AtomicInteger observations = new AtomicInteger();

        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeGeneratedSources("""
                        [generated.main.api]
                        kind = "openapi"
                        input = "api.yaml"
                        unknown = true
                        """, ignored -> observations.incrementAndGet()));

        assertTrue(
                failure.getMessage().contains("Unknown manifest field"),
                failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, observations.get());
    }

    @Test
    void doesNotObserveOmissionAndRequiresNonNullInputs() {
        AtomicInteger observations = new AtomicInteger();

        assertTrue(decodeGeneratedSources(
                "", ignored -> observations.incrementAndGet()).isEmpty());
        assertEquals(0, observations.get());

        NullPointerException indexFailure = assertThrows(
                NullPointerException.class,
                () -> decodeGeneratedSourcesWithNullIndex());
        assertEquals("Manifest decode index is required.", indexFailure.getMessage());
        NullPointerException observerFailure = assertThrows(
                NullPointerException.class,
                () -> decodeGeneratedSources("", null));
        assertEquals(
                "Authored generated sources presence observer is required.",
                observerFailure.getMessage());
    }

    private static void assertObservedFailure(String source, String section) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeGeneratedSources(source, ignored -> {
                    throw new IllegalArgumentException("Observed authored generated sources.");
                }));
        assertTrue(failure.getMessage().contains(section), failure.getMessage());
        assertTrue(
                failure.getMessage().contains("Observed authored generated sources."),
                failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    private static void assertObservedThenLeafFailure(String source, String path) {
        AtomicInteger observations = new AtomicInteger();
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decodeGeneratedSources(
                        source, ignored -> observations.incrementAndGet()));
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(1, observations.get());
    }
}
